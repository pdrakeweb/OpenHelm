package dev.openhelm.app.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.openhelm.app.ui.icons.MfdIcons

/**
 * Chooses between the two ways of using the remote: **Mirror** and **Remote only**.
 *
 * This replaces a single *Video off* / *Video on* button, which was the wrong shape for the job in
 * a way that is worth writing down. A one-word toggle whose label changes has to be read twice:
 * "Video off" is equally readable as *the video is off* and as *tap to turn the video off*, and the
 * two readings are exact opposites. There is no wording that fixes it — *Video on* has the same
 * ambiguity in mirror image — because the problem is the control, not the copy. Worse, it framed
 * the choice as a feature being switched off, when in fact both sides are legitimate ways to run:
 * remote-only is what you want when the chart is being read off the display itself, and it is the
 * fallback when video is the broken half.
 *
 * Two labelled segments with one marked show the current mode and the alternative at the same
 * time, so nothing has to be inferred from a verb.
 *
 * Both segments stay ≥[MinHelmTarget] tall; Material's own segmented buttons are 40dp, which is
 * under even the platform minimum and well under what a wet hand on a moving deck needs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrorModeSwitch(
    mirroring: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.height(MinHelmTarget)) {
        ModeSegment(
            selected = mirroring,
            onClick = { onSelect(true) },
            index = 0,
            glyph = MfdIcons.VideoOn,
            label = "Mirror",
            spoken = "Mirror the display's screen beside the controls",
        )
        ModeSegment(
            selected = !mirroring,
            onClick = { onSelect(false) },
            index = 1,
            glyph = MfdIcons.Keypad,
            label = "Remote",
            spoken = "Remote only — full-screen controls, no video",
        )
    }
}

/**
 * One segment.
 *
 * The selected one is marked three ways at once — a **tick** in place of its glyph, a lifted
 * container, and bold text. That is not belt-and-braces for its own sake: the first attempt kept
 * the mode glyph on both segments and let Material's default `activeContainerColor` carry the
 * state alone, and in the night palette that container sits a few points above the surface, so on
 * a screenshot the two segments were very nearly identical. A control whose entire job is saying
 * which mode you are in cannot be the one thing on screen you have to squint at.
 *
 * The tick is Material's own convention for a selected segment, and swapping it in for the glyph
 * costs nothing: the label already names the mode, and the unselected segment keeps its glyph, so
 * the pair still reads as picture-versus-keypad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.material3.SingleChoiceSegmentedButtonRowScope.ModeSegment(
    selected: Boolean,
    onClick: () -> Unit,
    index: Int,
    glyph: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    spoken: String,
) {
    SegmentedButton(
        selected = selected,
        onClick = onClick,
        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
        colors = SegmentedButtonDefaults.colors(
            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            activeBorderColor = MaterialTheme.colorScheme.primary,
        ),
        icon = {
            Icon(
                imageVector = if (selected) MfdIcons.Confirm else glyph,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = Modifier
            .height(MinHelmTarget)
            .semantics { contentDescription = spoken },
    ) {
        Text(
            text = label,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
