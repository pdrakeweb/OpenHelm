package dev.openhelm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.openhelm.app.ui.icons.MfdIcons

/**
 * Steps the display through high contrast → dark → night, from inside a live session.
 *
 * Reachable while connected on purpose. Light conditions change during a passage, and the moment
 * the screen is too bright is exactly the moment when disconnecting to go and find a settings
 * screen is least acceptable — a chart that has to be abandoned to be dimmed will simply be
 * endured instead, and a white screen at night costs twenty minutes of night vision.
 *
 * A cycle rather than a menu or a dialog: one tap, no target to hit inside a popup, and three taps
 * returns to where it started, so a mis-tap in the dark is self-correcting. The button shows the
 * palette that is **currently in effect**, not the one the next tap will select; a control that
 * previews its own future state is unreadable when you cannot remember which way round it was.
 */
/**
 * Knocks the video back in night mode. A no-op in every other palette.
 *
 * Night mode has to reach the picture, not just the app's own surfaces: the chart is by far the
 * largest and brightest thing on screen, so a themed UI wrapped around an undimmed 800×480 video
 * destroys night vision about as thoroughly as no night mode at all.
 *
 * Shared between the real and simulated panes deliberately. The first version of this lived inside
 * the real pane only, and simulation — whose whole justification is previewing what ships — showed
 * a fully bright chart under a night-mode UI.
 *
 * Call inside a `Box`; it fills the parent. Carries no pointer input, so it dims the chart without
 * swallowing touches meant for it.
 */
@Composable
fun BoxScope.NightDim(palette: HelmPalette) {
    if (palette != HelmPalette.NIGHT) return
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = NIGHT_DIM_ALPHA)),
    )
}

/**
 * How far night mode knocks the picture back.
 *
 * Enough to stop the chart being the brightest object in a dark cockpit, not so much that a feature
 * the user is steering by disappears. Deliberately gentler than the stale-video scrim (0xD9), which
 * is meant to be unmissable; this one is meant to be barely noticed.
 */
private const val NIGHT_DIM_ALPHA = 0.55f

@Composable
fun PaletteButton(palette: HelmPalette, onCycle: () -> Unit, modifier: Modifier = Modifier) {
    val (icon, name) = when (palette) {
        HelmPalette.HIGH_CONTRAST -> MfdIcons.PaletteDay to "High contrast"
        HelmPalette.DARK -> MfdIcons.PaletteDusk to "Dark"
        HelmPalette.NIGHT -> MfdIcons.PaletteNight to "Night"
    }

    FilledTonalIconButton(
        onClick = onCycle,
        modifier = modifier
            .size(MinHelmTarget)
            .semantics {
                // The label names the control, the state names where it is — so a screen reader
                // announces "Screen brightness, Day" rather than just an icon with no context.
                contentDescription = "Screen brightness"
                stateDescription = name
            },
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}
