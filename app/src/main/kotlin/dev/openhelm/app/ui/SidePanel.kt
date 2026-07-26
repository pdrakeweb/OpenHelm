package dev.openhelm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openhelm.protocol.MfdKey

/**
 * The control panel beside the video — the settled layout, arrived at by using the predecessor
 * app on the water: 180dp wide, two 80×64 keys per row with 4dp gaps, the dial large and centred
 * in a weighted row so the panel fills the height, BACK full-width directly *below* the dial
 * (it is the most-reached-for key after the dial), and the mode switch at the bottom.
 *
 * 64dp key height is deliberately above the platform minimum: wet hands, gloves, and a deck that
 * moves.
 */
@Composable
fun SidePanel(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(PANEL_WIDTH)
            .fillMaxHeight()
            .padding(GAP),
        verticalArrangement = Arrangement.spacedBy(GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KeyPairRow("Home" to MfdKey.HOME, "Menu" to MfdKey.MENU, viewModel)

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Dial(
                onKeyDown = viewModel::keyDown,
                onKeyUp = viewModel::keyUp,
                onRotate = viewModel::zoomStep,
            )
        }

        WideKey("Back", onDown = { viewModel.keyDown(MfdKey.BACK) }, onUp = { viewModel.keyUp(MfdKey.BACK) })
        KeyPairRow("Rng −" to MfdKey.RANGE_OUT, "Rng +" to MfdKey.RANGE_IN, viewModel)
        KeyPairRow("Swch" to MfdKey.SWITCH, "WPT" to MfdKey.WPT, viewModel)
        ModeSwitchKey(viewModel)
    }
}

@Composable
private fun KeyPairRow(
    left: Pair<String, MfdKey>,
    right: Pair<String, MfdKey>,
    viewModel: MainViewModel,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
        LabelKey(
            left.first,
            onDown = { viewModel.keyDown(left.second) },
            onUp = { viewModel.keyUp(left.second) },
            size = KEY_HEIGHT,
            width = KEY_WIDTH,
        )
        LabelKey(
            right.first,
            onDown = { viewModel.keyDown(right.second) },
            onUp = { viewModel.keyUp(right.second) },
            size = KEY_HEIGHT,
            width = KEY_WIDTH,
        )
    }
}

@Composable
private fun WideKey(label: String, onDown: () -> Unit, onUp: () -> Unit) {
    LabelKey(
        label,
        onDown = onDown,
        onUp = onUp,
        size = KEY_HEIGHT,
        width = WIDE_WIDTH,
    )
}

/** Switches to the full-screen remote. A mode change, not an MFD key — a plain click. */
@Composable
private fun ModeSwitchKey(viewModel: MainViewModel) {
    Box(
        modifier = Modifier
            .width(WIDE_WIDTH)
            .height(KEY_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .clickable { viewModel.toggleVideo() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Remote Ctrl",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// The settled measurements: two 80dp keys + one 4dp gap = the 164dp wide keys, inside a 180dp
// panel with 4dp outer padding. Starting points, not sacred numbers.
private val PANEL_WIDTH = 180.dp
private val KEY_WIDTH = 80.dp
private val KEY_HEIGHT = 64.dp
private val WIDE_WIDTH = 164.dp
private val GAP = 4.dp
