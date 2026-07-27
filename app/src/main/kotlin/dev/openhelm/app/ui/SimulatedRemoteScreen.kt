package dev.openhelm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.openhelm.app.ui.icons.MfdIcons

/**
 * Simulation mode: the same remote UI as a real session, but with a video feed generated on the
 * device and a keypad that talks to nothing. Entered and left from [SettingsScreen]; the flag it
 * runs on is session-only, never persisted, so nobody launches into a stale simulated session.
 *
 * Reuses [SidePanel] and [Keypad] as-is — they already drive through [MainViewModel.keyDown] /
 * [MainViewModel.keyUp], which safely do nothing while there is no real connection ([currentEndpoint]
 * is null), so the controls are genuinely tappable with real press/release feedback and simply have
 * nowhere to send. The Mirror/Remote switch drives the same [MainViewModel.mirroring] flag the
 * real screen uses, so both modes behave identically to the real thing.
 */
@Composable
fun SimulatedRemoteScreen(viewModel: MainViewModel, palette: HelmPalette) {
    LockLandscape()
    // Simulation exists so the layout that ships can be inspected without a display. That only
    // holds if it composes the same window treatment: without this, the bars stayed visible here
    // and simulation showed a window shape the real session never has.
    ImmersiveWhileConnected()

    // Same shape as RemoteScreen's Back handling: leave full-screen before leaving simulation.
    BackHandler {
        if (!viewModel.mirroring) viewModel.selectMirroring(true) else viewModel.exitSimulation()
    }

    // Same shape as the real remote: the bar sits over the picture so the panel owns the full
    // height. See RemoteScreen.
    if (viewModel.mirroring) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                SimulationStatusBar(viewModel, palette)
                SimulatedVideoPane(palette, Modifier.weight(1f).fillMaxWidth())
            }
            SidePanel(viewModel)
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            SimulationStatusBar(viewModel, palette)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Keypad(
                    onKeyDown = viewModel::keyDown,
                    onKeyUp = viewModel::keyUp,
                    onRotate = viewModel::zoomStep,
                )
            }
        }
    }
}

@Composable
private fun SimulationStatusBar(viewModel: MainViewModel, palette: HelmPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StatusBarHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "Simulated display",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.weight(1f))
        // Same position and behaviour as the real status bar — simulation is only useful as a
        // preview if the controls it shows are the ones that ship.
        PaletteButton(palette = palette, onCycle = { viewModel.cyclePalette(palette) })
        Spacer(Modifier.width(8.dp))
        MirrorModeSwitch(
            mirroring = viewModel.mirroring,
            onSelect = viewModel::selectMirroring,
        )
        Spacer(Modifier.width(8.dp))
        NavActionButton(
            icon = MfdIcons.Disconnect,
            label = "End simulation",
            onClick = viewModel::exitSimulation,
        )
    }
}

/**
 * The display's picture, stood in for by a chart scene generated on the device.
 *
 * See [drawSimulatedChart] for what it draws and why none of it comes from a photograph. Labelled
 * `SIMULATED` throughout — this must never be mistakable for a real session, and that badge is the
 * only thing separating a convincing fake chart from one someone might navigate by.
 */
@Composable
private fun SimulatedVideoPane(palette: HelmPalette, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    val measurer = rememberTextMeasurer()

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastNanos == 0L || now - lastNanos >= FRAME_INTERVAL_NANOS) {
                    lastNanos = now
                    frame++
                }
            }
        }
    }

    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        // Same 5:3 fit as the real pane, via the same helper — so what simulation shows is the
        // layout that ships, including at window shapes where a naive aspectRatio would overflow.
        Box(letterboxModifier(maxWidth, maxHeight)) {
            Canvas(Modifier.fillMaxSize()) {
                drawSimulatedChart(measurer, frame)
            }

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .background(Color(0xCC000000))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    "SIMULATED",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    "not a real display · frame $frame",
                    color = Color(0xCCE8EEF4),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }

        // Same night dimming as the real pane, from the same helper.
        NightDim(palette)
    }
}

private const val FRAME_INTERVAL_NANOS = 1_000_000_000L / 15L
