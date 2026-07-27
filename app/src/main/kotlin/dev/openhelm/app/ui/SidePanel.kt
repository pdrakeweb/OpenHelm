package dev.openhelm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The control panel beside the video.
 *
 * The proportions come from using the predecessor app on the water — two keys across (three made
 * them too small to hit reliably on a moving deck), the dial large and centred, and the
 * most-reached-for keys nearest it. What has changed is that none of it is fixed any more: the
 * panel **measures the height it was actually given** and picks a size band to match.
 *
 * That adaptivity is a defect fix, not a refinement. With hard-coded dp the panel overflowed a
 * compact-height phone window and the dial's ring drew straight through the keys below it —
 * overlapping hit areas on a screen whose buttons send real commands to a display that may be
 * driving an autopilot. It also wasted roughly 250dp of a 10.9" tablet by reusing phone-sized
 * metrics verbatim.
 *
 * Whatever band is chosen, no key is ever smaller than [MinHelmTarget]; if even the smallest band
 * cannot fit, the panel scrolls rather than overlapping itself.
 */
@Composable
fun SidePanel(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        val metrics = panelMetricsFor(maxHeight)

        Column(
            modifier = Modifier
                .width(metrics.panelWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(metrics.gap),
            verticalArrangement = Arrangement.spacedBy(metrics.gap, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The named keys, two across, in the canonical order shared with the keypad.
            MfdControl.panelOrder.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
                    row.forEach { control ->
                        ControlKey(
                            control = control,
                            onDown = { viewModel.keyDown(control.key) },
                            onUp = { viewModel.keyUp(control.key) },
                            size = metrics.keyHeight,
                            width = metrics.keyWidth,
                            showLabel = metrics.showLabels,
                        )
                    }
                    if (row.size == 1) Box(Modifier.width(metrics.keyWidth))
                }
            }

            Dial(
                onKeyDown = viewModel::keyDown,
                onKeyUp = viewModel::keyUp,
                onRotate = viewModel::zoomStep,
                size = metrics.dialSize,
            )
        }
    }
}

/** The size band this panel will use, derived from the height it was actually handed. */
private data class PanelMetrics(
    val panelWidth: Dp,
    val keyWidth: Dp,
    val keyHeight: Dp,
    val dialSize: Dp,
    val gap: Dp,
    val showLabels: Boolean,
)

/**
 * Pick a size band from the available height.
 *
 * Three bands rather than a continuous scale, so the layout is predictable and testable. The
 * thresholds are the heights at which the next band up stops fitting: four key rows plus the dial
 * plus gaps. Labels are dropped in the tightest band before key size is — a smaller target is a
 * safety problem, a missing word is not, and every key keeps its spoken description regardless.
 */
private fun panelMetricsFor(availableHeight: Dp): PanelMetrics = when {
    // Expanded — a tablet at a nav station. Use the room.
    availableHeight >= 820.dp -> PanelMetrics(
        panelWidth = 260.dp,
        keyWidth = 118.dp,
        keyHeight = 88.dp,
        dialSize = 216.dp,
        gap = 8.dp,
        showLabels = true,
    )

    // Medium — a large phone in landscape, or a small tablet.
    availableHeight >= 560.dp -> PanelMetrics(
        panelWidth = 208.dp,
        keyWidth = 94.dp,
        keyHeight = 68.dp,
        dialSize = 168.dp,
        gap = 6.dp,
        showLabels = true,
    )

    // Compact height — a phone in landscape. This is the band the old fixed layout overflowed.
    //
    // The width is derived, not chosen: it must hold the dial at its own floor, or the dial gets
    // a narrower box than it asked for and its drawn and tappable geometry come apart. Two keys
    // plus their gap also has to fit, so take whichever is larger.
    else -> {
        val compactKeyWidth = MinHelmTarget + 10.dp
        val gap = 4.dp
        PanelMetrics(
            panelWidth = maxOf(MinDialSize, compactKeyWidth * 2 + gap) + gap * 2,
            keyWidth = compactKeyWidth,
            keyHeight = MinHelmTarget,
            dialSize = MinDialSize,
            gap = gap,
            showLabels = false,
        )
    }
}
