package dev.openhelm.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import dev.openhelm.protocol.MfdKey

/** Which way an arrow key points. */
enum class ArrowDirection { UP, DOWN, LEFT, RIGHT }

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
    val background =
        if (pressed) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            // Never smaller than the helm minimum, whatever a caller asks for.
            .sizeIn(minWidth = MinHelmTarget, minHeight = MinHelmTarget)
            .width(width)
            .height(size)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    // Haptic on contact: underway, eyes are on the water, and a felt press is
                    // the only confirmation the finger landed.
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
        val tint =
            if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
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
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A key showing a short text label. All labels here are this project's own wording. */
@Composable
fun LabelKey(
    label: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    width: Dp = size,
) {
    MfdKeyButton(
        onDown = onDown,
        onUp = onUp,
        modifier = modifier,
        size = size,
        width = width,
        contentDescription = label,
    ) { pressed ->
        Text(
            text = label,
            color = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontSize = if (label.length > 2) 14.sp else 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** A key showing a triangle arrow, drawn right here — no icon assets. */
@Composable
fun ArrowKey(
    direction: ArrowDirection,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    val label = when (direction) {
        ArrowDirection.UP -> "Move the cursor up"
        ArrowDirection.DOWN -> "Move the cursor down"
        ArrowDirection.LEFT -> "Move the cursor left"
        ArrowDirection.RIGHT -> "Move the cursor right"
    }
    MfdKeyButton(
        onDown = onDown,
        onUp = onUp,
        modifier = modifier,
        size = size,
        contentDescription = label,
    ) { pressed ->
        val color =
            if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        Canvas(modifier = Modifier.size(iconSizeFor(size))) {
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                when (direction) {
                    ArrowDirection.UP -> {
                        moveTo(w / 2f, 0f); lineTo(w, h); lineTo(0f, h)
                    }
                    ArrowDirection.DOWN -> {
                        moveTo(0f, 0f); lineTo(w, 0f); lineTo(w / 2f, h)
                    }
                    ArrowDirection.LEFT -> {
                        moveTo(w, 0f); lineTo(w, h); lineTo(0f, h / 2f)
                    }
                    ArrowDirection.RIGHT -> {
                        moveTo(0f, 0f); lineTo(w, h / 2f); lineTo(0f, h)
                    }
                }
                close()
            }
            drawPath(path, color)
        }
    }
}

/** Icon glyphs scale with their key, within sane bounds, so every mode looks like one family. */
private fun iconSizeFor(keySize: Dp): Dp = (keySize * 0.34f).coerceIn(18.dp, 30.dp)

/**
 * The full-screen keypad, for the control-only remote mode: a directional cluster with OK in the
 * middle, and the display's named keys beside it. The grouping mirrors the *hardware* front
 * panel's functions — a fact of the device, not a copied layout — and the labels, glyphs and
 * order come from [MfdControl.panelOrder], the same source the side panel uses.
 */
@Composable
fun Keypad(
    onKeyDown: (MfdKey) -> Unit,
    onKeyUp: (MfdKey) -> Unit,
    modifier: Modifier = Modifier,
    keySize: Dp = 72.dp,
) {
    val size = keySize.coerceAtLeast(MinHelmTarget)
    val gap = 8.dp
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DirectionCluster(onKeyDown, onKeyUp, size, gap)
        NamedKeyCluster(onKeyDown, onKeyUp, size, gap)
    }
}

/** Hold to sweep the cursor (the display auto-repeats), tap for ~1 px. */
@Composable
private fun DirectionCluster(
    onKeyDown: (MfdKey) -> Unit,
    onKeyUp: (MfdKey) -> Unit,
    keySize: Dp,
    gap: Dp,
) {
    fun handlers(key: MfdKey): Pair<() -> Unit, () -> Unit> =
        Pair({ onKeyDown(key) }, { onKeyUp(key) })

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        handlers(MfdKey.UP).let { (d, u) -> ArrowKey(ArrowDirection.UP, d, u, size = keySize) }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            handlers(MfdKey.LEFT).let { (d, u) -> ArrowKey(ArrowDirection.LEFT, d, u, size = keySize) }
            handlers(MfdKey.OK).let { (d, u) -> LabelKey("OK", d, u, size = keySize) }
            handlers(MfdKey.RIGHT).let { (d, u) -> ArrowKey(ArrowDirection.RIGHT, d, u, size = keySize) }
        }
        handlers(MfdKey.DOWN).let { (d, u) -> ArrowKey(ArrowDirection.DOWN, d, u, size = keySize) }
    }
}

/** The named keys, in the canonical order shared with the side panel. */
@Composable
private fun NamedKeyCluster(
    onKeyDown: (MfdKey) -> Unit,
    onKeyUp: (MfdKey) -> Unit,
    keySize: Dp,
    gap: Dp,
) {
    // Two across, in panelOrder, so the keypad and the side panel present the same sequence.
    val rows = MfdControl.panelOrder.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { control ->
                    ControlKey(
                        control = control,
                        onDown = { onKeyDown(control.key) },
                        onUp = { onKeyUp(control.key) },
                        size = keySize,
                    )
                }
                if (row.size == 1) Spacer(Modifier.width(keySize))
            }
        }
    }
}
