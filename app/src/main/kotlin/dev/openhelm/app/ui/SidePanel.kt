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
            PanelLayout.forEach { row ->
                when (row) {
                    is PanelRow.Keys -> Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
                        row.controls.forEach { control ->
                            ControlKey(
                                control = control,
                                onDown = { viewModel.keyDown(control.key) },
                                onUp = { viewModel.keyUp(control.key) },
                                size = metrics.keyHeight,
                                width = metrics.keyWidth,
                                showLabel = metrics.showLabels,
                            )
                        }
                        if (row.controls.size == 1) Box(Modifier.width(metrics.keyWidth))
                    }

                    is PanelRow.WideKey -> ControlKey(
                        control = row.control,
                        onDown = { viewModel.keyDown(row.control.key) },
                        onUp = { viewModel.keyUp(row.control.key) },
                        size = metrics.keyHeight,
                        width = metrics.wideKeyWidth,
                        showLabel = metrics.showLabels,
                    )

                    PanelRow.DialRow -> Dial(
                        onKeyDown = viewModel::keyDown,
                        onKeyUp = viewModel::keyUp,
                        onRotate = viewModel::zoomStep,
                        size = metrics.dialSize,
                    )
                }
            }
        }
    }
}

/** One band of the panel. */
internal sealed interface PanelRow {
    /** One or two keys side by side. */
    data class Keys(val controls: List<MfdControl>) : PanelRow

    /** A single key spanning the full panel width. */
    data class WideKey(val control: MfdControl) : PanelRow

    /** The rotary dial. */
    data object DialRow : PanelRow
}

/**
 * Where each control sits, top to bottom.
 *
 * This is a **spatial arrangement, not a sorted list**, which is why it is written out rather than
 * derived by chunking [MfdControl.panelOrder] into pairs. Chunking put the dial at the bottom and
 * the keys in whatever pairs fell out of the enum order; the result read as a list of buttons
 * rather than as a control panel, and Back — reached for constantly while walking a menu — ended up
 * buried in the middle of a pair.
 *
 * The order here is the one asked for at the helm: the two navigation keys at the top, the dial
 * directly under the thumb in the middle where the hand rests, Back given a full-width row of its
 * own immediately below it so it is the easiest thing to hit without looking, then zoom, then the
 * rest.
 *
 * Every control appears exactly once — checked by `PanelLayoutTest` against [MfdControl.panelOrder],
 * so adding a control to the enum and forgetting it here fails the build rather than silently
 * removing a key from the panel.
 */
private val PanelLayout: List<PanelRow> = listOf(
    PanelRow.Keys(listOf(MfdControl.HOME, MfdControl.MENU)),
    PanelRow.DialRow,
    PanelRow.WideKey(MfdControl.BACK),
    PanelRow.Keys(listOf(MfdControl.ZOOM_IN, MfdControl.ZOOM_OUT)),
    PanelRow.Keys(listOf(MfdControl.PANE, MfdControl.WAYPOINT)),
)

/**
 * The panel's rows with the dial removed.
 *
 * The full-screen remote uses this for its named keys and places the dial as a separate cluster
 * beside them, so the two modes present the same controls in the same order — including Back on a
 * row of its own — while using the landscape space differently.
 */
internal val namedPanelRows: List<PanelRow> = PanelLayout.filterNot { it is PanelRow.DialRow }

/** The controls the panel actually places, in visual order. Exposed for the layout test. */
internal val panelLayoutControls: List<MfdControl>
    get() = PanelLayout.flatMap { row ->
        when (row) {
            is PanelRow.Keys -> row.controls
            is PanelRow.WideKey -> listOf(row.control)
            PanelRow.DialRow -> emptyList()
        }
    }

/**
 * The size band this panel will use, derived from the height it was actually handed.
 *
 * Internal rather than private because the status bar above the panel uses it too: its trailing
 * action is sized and inset to match [wideKeyWidth], so that button and the panel's full-width Back
 * key share an edge down the right of the screen instead of being two arbitrary widths.
 */
internal data class PanelMetrics(
    val panelWidth: Dp,
    val keyWidth: Dp,
    val keyHeight: Dp,
    val dialSize: Dp,
    val gap: Dp,
    val showLabels: Boolean,
) {
    /** A key spanning the pair above and below it, gap included. */
    val wideKeyWidth: Dp get() = keyWidth * 2 + gap
}

/**
 * The height the status bar is fixed at.
 *
 * Fixed rather than measured so the panel's band can be worked out before either is laid out: the
 * band depends on the height left under the bar, and the bar's trailing button depends on the band.
 * Measuring both would be circular. It is exactly a helm target plus its padding, which is what the
 * bar contained anyway.
 */
internal val StatusBarHeight: Dp = MinHelmTarget + 8.dp

/**
 * Pick a size band from the available height.
 *
 * Three bands rather than a continuous scale, so the layout is predictable and testable.
 *
 * Each threshold is what that band actually needs — four key rows, the dial, the gaps between them
 * and the panel's own padding — and nothing more. They used to be considerably higher than that,
 * set when the panel sat under a full-width status bar and never saw the top of the screen. With
 * the panel running the full height the old numbers left a tablet in the middle band with about
 * 180dp of unused height and keys a third smaller than would fit.
 *
 * Labels are dropped in the tightest band before key size is — a smaller target is a safety
 * problem, a missing word is not, and every key keeps its spoken description regardless.
 */
internal fun panelMetricsFor(availableHeight: Dp): PanelMetrics = when {
    // Expanded — a tablet at a nav station. Needs 4*88 + 216 + 4*8 + 16 = 616dp.
    availableHeight >= 632.dp -> PanelMetrics(
        panelWidth = 260.dp,
        keyWidth = 118.dp,
        keyHeight = 88.dp,
        dialSize = 216.dp,
        gap = 8.dp,
        showLabels = true,
    )

    // Medium — a large phone in landscape, or a small tablet. Needs 4*68 + 168 + 4*6 + 12 = 476dp.
    availableHeight >= 492.dp -> PanelMetrics(
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
