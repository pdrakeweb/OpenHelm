package dev.openhelm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
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

    var showSettings by remember { mutableStateOf(false) }

    // Same shape as RemoteScreen's Back handling: leave full-screen before leaving simulation.
    BackHandler(enabled = !showSettings) {
        if (!viewModel.mirroring) viewModel.selectMirroring(true) else viewModel.exitSimulation()
    }

    // Same shape as the real remote — actions on a rail down the outside edge, nothing across the
    // top. Simulation is only worth having if the layout it previews is the one that ships.
    val rail: @Composable () -> Unit = {
        HelmActionRail(
            palette = palette,
            mirroring = viewModel.mirroring,
            onCyclePalette = { viewModel.cyclePalette(palette) },
            onSelectMirroring = viewModel::selectMirroring,
            exitIcon = MfdIcons.Disconnect,
            exitLabel = "End simulation",
            onExit = viewModel::exitSimulation,
            onSettings = { showSettings = true },
        )
    }

    if (viewModel.mirroring) {
        Row(Modifier.fillMaxSize().padding(horizontal = HelmEdgeInset)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                SimulatedVideoPane(
                    palette = palette,
                    onAction = viewModel::noteSimAction,
                    modifier = Modifier.fillMaxSize(),
                )
                SimActionField(
                    action = viewModel.simAction,
                    repeats = viewModel.simActionRepeats,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
            SidePanel(viewModel)
            rail()
        }
    } else {
        Row(Modifier.fillMaxSize().padding(horizontal = HelmEdgeInset)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Keypad(
                        onKeyDown = viewModel::keyDown,
                        onKeyUp = viewModel::keyUp,
                        onRotate = viewModel::zoomStep,
                    )
                }
                SimActionField(
                    action = viewModel.simAction,
                    repeats = viewModel.simActionRepeats,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
            rail()
        }
    }

    // Over the top, like the real session — simulation exists to preview what ships, and that
    // includes how you reach Settings from inside a session.
    if (showSettings) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
        ) {
            SettingsScreen(viewModel, palette, onDone = { showSettings = false })
        }
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
private fun SimulatedVideoPane(
    palette: HelmPalette,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableIntStateOf(0) }
    val measurer = rememberTextMeasurer()
    val view = LocalView.current

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // Gesture feedback. Simulation's chart cannot respond, so a tap, a drag and a pinch otherwise
    // all look the same: nothing happens.
    val trail = remember { TouchMarkTrail() }
    var touching by remember { mutableStateOf(false) }
    val markClock = rememberTouchMarkClock(trail, touching)
    val markColor = MaterialTheme.colorScheme.primary

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

    BoxWithConstraints(
        modifier.background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        // Same 5:3 fit as the real pane, via the same helper — so what simulation shows is the
        // layout that ships, including at window shapes where a naive aspectRatio would overflow.
        Box(letterboxModifier(maxWidth, maxHeight)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    // The local pinch zoom, applied to the scene exactly as the real pane applies
                    // it to its TextureView. Without this the gesture changed the state and
                    // nothing moved, which is worse than not offering it.
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = pan.x
                        translationY = pan.y
                    },
            ) {
                drawSimulatedChart(measurer, frame)
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // The real pane's gesture handling, not a copy of it — see
                        // videoTouchGestures. Simulation is only worth having if the thing it
                        // exercises is the thing that ships.
                        videoTouchGestures(
                            scaleOf = { scale },
                            panOf = { pan },
                            onTransform = { s, p -> scale = s; pan = p },
                            onDown = { x, y, size -> onAction(touchLabel("Touch", x, y, size)) },
                            onMove = { x, y, size -> onAction(touchLabel("Drag", x, y, size)) },
                            onUp = { _, _, _ -> },
                            onGesture = { gesture ->
                                when (gesture) {
                                    is VideoGesture.Touch -> {
                                        touching = true
                                        trail.add(gesture.position, pinch = false, nowNanos = System.nanoTime())
                                    }
                                    is VideoGesture.Pinch -> {
                                        touching = true
                                        gesture.positions.forEach {
                                            trail.add(it, pinch = true, nowNanos = System.nanoTime())
                                        }
                                        onAction("Pinch zoom ×" + String.format(java.util.Locale.ROOT, "%.1f", gesture.scale))
                                    }
                                    VideoGesture.End -> touching = false
                                }
                            },
                            onHaptic = { moment ->
                                when (moment) {
                                    HapticMoment.TOUCH_DOWN -> HelmHaptics.touchDown(view)
                                    HapticMoment.STEP -> HelmHaptics.gestureStep(view)
                                }
                            },
                        )
                    },
            )

            // Touch marks sit outside the zoom: they mark where the finger was on the glass, not
            // where it landed on the chart.
            Canvas(Modifier.fillMaxSize()) {
                trail.draw(this, markClock, markColor)
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

/** "Touch 43%, 58%" — position as a share of the picture, which is what the wire format carries. */
private fun touchLabel(verb: String, x: Float, y: Float, size: androidx.compose.ui.unit.IntSize): String {
    val px = if (size.width > 0) (x / size.width * 100).toInt() else 0
    val py = if (size.height > 0) (y / size.height * 100).toInt() else 0
    return "$verb $px%, $py%"
}

private const val FRAME_INTERVAL_NANOS = 1_000_000_000L / 15L

/**
 * Names the last thing the user did.
 *
 * Simulation's chart cannot react, so a control that works and a control wired to nothing look
 * identical — the whole mode is otherwise unfalsifiable. This is the substitute for the display
 * responding: press Zoom out and the field says "Zoom out".
 *
 * The empty state is an empty bordered box rather than a line of instructions. The border says
 * "something appears here" as well as a sentence does, and it says it once instead of occupying the
 * width permanently with text that stops being true after the first press.
 *
 * Repeats collapse into a count rather than scrolling, because holding a direction key produces one
 * press and it is the repetition that is interesting, not a list of identical lines.
 */
@Composable
private fun SimActionField(action: String?, repeats: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(MinHelmTarget - 12.dp)
            // Opaque, because it now sits over the picture rather than in a bar beside it — and
            // the picture's own data bar occupies exactly this corner. A bordered box with the
            // chart showing through it put two lines of small text on top of each other.
            .background(Color(0xB3000000), MaterialTheme.shapes.small)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (action != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (repeats > 1) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "×$repeats",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}
