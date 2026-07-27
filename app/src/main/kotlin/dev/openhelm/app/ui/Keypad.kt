package dev.openhelm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import dev.openhelm.protocol.MfdKey

/**
 * Minimum size for anything on the remote that takes a press.
 *
 * Material's own floor is 48dp; this is deliberately above it. The device is used one-handed on a
 * moving deck, often with wet or gloved hands, and a mis-tap here sends a real command to a display
 * that may be driving an autopilot. No control on the remote screen may be smaller than this.
 */
val MinHelmTarget: Dp = 56.dp

/**
 * One physical-feeling key.
 *
 * The gesture handling is the point of this composable: [onDown] fires on real finger contact and
 * [onUp] on real release *or cancellation*. The press duration is transmitted to the MFD, whose
 * own auto-repeat turns a hold into continuous cursor movement — so this must never collapse a
 * press into a synthetic click. Cancellation also sends UP: leaving a key logically held after the
 * finger slid away would auto-repeat forever.
 */
@Composable
fun MfdKeyButton(
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    width: Dp = size,
    contentDescription: String? = null,
    content: @Composable (pressed: Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val controls = LocalHelmControls.current
    val background = if (pressed) controls.keyPressedFill else controls.keyFill

    Box(
        modifier = modifier
            // Never smaller than the helm minimum, whatever a caller asks for.
            .sizeIn(minWidth = MinHelmTarget, minHeight = MinHelmTarget)
            .width(width)
            .height(size)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .then(
                // Only the high-contrast palette asks for one; elsewhere the fill already separates
                // the key from the panel and a border would be noise.
                if (controls.keyBorderWidth > 0.dp) {
                    Modifier.border(controls.keyBorderWidth, controls.keyBorder, MaterialTheme.shapes.medium)
                } else {
                    Modifier
                },
            )
            // Accessibility has to be declared explicitly: this is a raw pointerInput on a Box, so
            // nothing about it is a button as far as the framework is concerned, and without a
            // role and an action TalkBack announced it as unlabelled static content and offered no
            // way to activate it. The action collapses the press into a down-then-up pair, which
            // is the right shape for an assistive activation — a screen-reader user cannot express
            // "hold", and a hold left open would auto-repeat on the display forever.
            .semantics {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
                onClick {
                    onDown()
                    onUp()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    // Haptic on contact: underway, eyes are on the water, and a felt press is
                    // the only confirmation the finger landed.
                    HelmHaptics.keyDown(view)
                    onDown()
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        // UP must always follow DOWN, even if the gesture is cancelled or this
                        // composable leaves composition mid-press.
                        pressed = false
                        onUp()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content(pressed)
    }
}

/**
 * A key for one of the display's controls: icon over label, from the single canonical
 * [MfdControl] table so the side panel and the keypad can never disagree.
 */
@Composable
fun ControlKey(
    control: MfdControl,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    width: Dp = size,
    showLabel: Boolean = true,
) {
    MfdKeyButton(
        onDown = onDown,
        onUp = onUp,
        modifier = modifier,
        size = size,
        width = width,
        contentDescription = control.description,
    ) { pressed ->
        val controls = LocalHelmControls.current
        val tint = if (pressed) controls.keyPressedContent else controls.keyContent
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = control.icon,
                contentDescription = null, // described on the button itself
                tint = tint,
                modifier = Modifier.size(iconSizeFor(size)),
            )
            if (showLabel) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = control.label,
                    color = tint,
                    fontSize = LabelTextSize,
                    lineHeight = LabelTextSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Icon glyphs scale with their key, within bounds, so every mode looks like one family.
 *
 * Weighted towards the glyph rather than the word. At arm's length on a moving boat the shape is
 * what gets recognised; the label is a confirmation you read once while learning the panel and
 * rarely again. The earlier split gave the two roughly equal presence, which meant the icon was
 * smaller than it needed to be at every key size.
 */
internal fun iconSizeFor(keySize: Dp): Dp = (keySize * 0.46f).coerceIn(24.dp, 40.dp)

/** Key labels sit under their glyph and stay out of its way. */
internal val LabelTextSize = 9.sp

/**
 * The full-screen keypad, for the control-only remote mode: a directional cluster with OK in the
 * middle, and the display's named keys beside it. The grouping mirrors the *hardware* front
 * panel's functions — a fact of the device, not a copied layout — and the labels, glyphs and
 * order come from [MfdControl.panelOrder], the same source the side panel uses.
 *
 * The key size is **measured, not assumed**. It used to be a hard-coded 72dp, which needs 312dp of
 * height for the named cluster alone; on a compact-height landscape phone that is more than the
 * window has once the status bar is taken out, and the bottom row was simply clipped away — a key
 * that is invisible but still counted in the layout is worse than one that is merely small. Now
 * the size is solved from the space actually given, floored at [MinHelmTarget], and if even the
 * floor does not fit the keypad scrolls instead of losing a row.
 */
@Composable
fun Keypad(
    onKeyDown: (MfdKey) -> Unit,
    onKeyUp: (MfdKey) -> Unit,
    onRotate: (step: Int, accumulated: Int) -> Unit,
    modifier: Modifier = Modifier,
    // Higher than the side panel's ceiling because this mode has the whole window. At 88dp a
    // tablet left most of the screen empty around a small cluster, which wastes the one advantage
    // remote-only has over the panel.
    maxKeySize: Dp = 120.dp,
) {
    val gap = 8.dp
    val clusterGap = 28.dp

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val size = keySizeFor(maxWidth, maxHeight, gap, clusterGap, maxKeySize)

        Row(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(clusterGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The same dial the side panel has, at three key rows across. Remote-only used to
            // offer four arrow keys and an OK instead, which quietly dropped the rotary zoom
            // altogether and meant the two modes taught different controls.
            Dial(
                onKeyDown = onKeyDown,
                onKeyUp = onKeyUp,
                onRotate = onRotate,
                size = size * 3 + gap * 2,
            )
            NamedKeyCluster(onKeyDown, onKeyUp, size, gap)
        }
    }
}

/**
 * Solve the largest key that fits both axes, then clamp.
 *
 * Vertically the tallest column is the named cluster: four rows of keys and three gaps. Across, the
 * two clusters sit side by side — three keys and two gaps for the directions, two keys and one gap
 * for the named ones, plus the gap between the clusters. Both are exact, so this is arithmetic
 * rather than a table of guessed breakpoints, and it cannot drift out of step with the layout the
 * way hand-tuned thresholds do.
 *
 * Kept internal and free of Compose so it can be unit-tested at window sizes no emulator offers.
 */
internal fun keySizeFor(
    availableWidth: Dp,
    availableHeight: Dp,
    gap: Dp,
    clusterGap: Dp,
    maxKeySize: Dp,
): Dp {
    val byHeight = (availableHeight - gap * 3) / 4
    val byWidth = (availableWidth - gap * 3 - clusterGap) / 5
    return minOf(byHeight, byWidth, maxKeySize).coerceAtLeast(MinHelmTarget)
}

/**
 * The named keys, in the same arrangement the side panel uses.
 *
 * Driven by [namedPanelRows] rather than by chunking the control list into pairs, so Back keeps the
 * full-width row it has on the panel instead of being paired off with whatever follows it. The two
 * modes are the same panel at two sizes; anything that makes them differ is a defect.
 */
@Composable
private fun NamedKeyCluster(
    onKeyDown: (MfdKey) -> Unit,
    onKeyUp: (MfdKey) -> Unit,
    keySize: Dp,
    gap: Dp,
) {
    val wide = keySize * 2 + gap
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        namedPanelRows.forEach { row ->
            when (row) {
                is PanelRow.Keys -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.controls.forEach { control ->
                        ControlKey(
                            control = control,
                            onDown = { onKeyDown(control.key) },
                            onUp = { onKeyUp(control.key) },
                            size = keySize,
                        )
                    }
                    if (row.controls.size == 1) Spacer(Modifier.width(keySize))
                }

                is PanelRow.WideKey -> ControlKey(
                    control = row.control,
                    onDown = { onKeyDown(row.control.key) },
                    onUp = { onKeyUp(row.control.key) },
                    size = keySize,
                    width = wide,
                )

                PanelRow.DialRow -> Unit // filtered out; the dial is its own cluster
            }
        }
    }
}
