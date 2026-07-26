package dev.openhelm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openhelm.protocol.MfdKey

/** Which way an arrow key points. */
enum class ArrowDirection { UP, DOWN, LEFT, RIGHT }

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
    content: @Composable (pressed: Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val background =
        if (pressed) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
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

/** A key showing a short text label. All labels here are this project's own wording. */
@Composable
fun LabelKey(
    label: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    MfdKeyButton(onDown = onDown, onUp = onUp, modifier = modifier, size = size) { pressed ->
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
    MfdKeyButton(onDown = onDown, onUp = onUp, modifier = modifier, size = size) { pressed ->
        val color =
            if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        Canvas(modifier = Modifier.size(size / 3)) {
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

/** How the two key clusters sit relative to each other. */
enum class KeypadArrangement {
    /** Side by side — the full-screen control-only layout. */
    WIDE,

    /** D-pad above the named keys — for the narrow panel beside the video. */
    STACKED,
}

/**
 * The keypad: a directional cluster with OK in the middle, and the MFD's named keys beside it.
 * The grouping mirrors the *hardware* front panel's functions — a fact of the device, not a
 * copied layout.
 */
@Composable
fun Keypad(
    onKeyDown: (MfdKey) -> Unit,
    onKeyUp: (MfdKey) -> Unit,
    modifier: Modifier = Modifier,
    keySize: Dp = 64.dp,
    arrangement: KeypadArrangement = KeypadArrangement.WIDE,
) {
    when (arrangement) {
        KeypadArrangement.WIDE -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DirectionCluster(onKeyDown, onKeyUp, keySize)
            NamedKeyCluster(onKeyDown, onKeyUp, keySize)
        }

        KeypadArrangement.STACKED -> Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DirectionCluster(onKeyDown, onKeyUp, keySize)
            NamedKeyCluster(onKeyDown, onKeyUp, keySize)
        }
    }
}

/** Hold to sweep the cursor (the MFD auto-repeats), tap for ~1 px. */
@Composable
private fun DirectionCluster(
    onKeyDown: (MfdKey) -> Unit,
    onKeyUp: (MfdKey) -> Unit,
    keySize: Dp,
) {
    fun handlers(key: MfdKey): Pair<() -> Unit, () -> Unit> =
        Pair({ onKeyDown(key) }, { onKeyUp(key) })

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        handlers(MfdKey.UP).let { (d, u) -> ArrowKey(ArrowDirection.UP, d, u, size = keySize) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            handlers(MfdKey.LEFT).let { (d, u) -> ArrowKey(ArrowDirection.LEFT, d, u, size = keySize) }
            handlers(MfdKey.OK).let { (d, u) -> LabelKey("OK", d, u, size = keySize) }
            handlers(MfdKey.RIGHT).let { (d, u) -> ArrowKey(ArrowDirection.RIGHT, d, u, size = keySize) }
        }
        handlers(MfdKey.DOWN).let { (d, u) -> ArrowKey(ArrowDirection.DOWN, d, u, size = keySize) }
    }
}

/** The named keys, arranged by how often they are reached for. */
@Composable
private fun NamedKeyCluster(
    onKeyDown: (MfdKey) -> Unit,
    onKeyUp: (MfdKey) -> Unit,
    keySize: Dp,
) {
    fun handlers(key: MfdKey): Pair<() -> Unit, () -> Unit> =
        Pair({ onKeyDown(key) }, { onKeyUp(key) })

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            handlers(MfdKey.RANGE_IN).let { (d, u) -> LabelKey("+", d, u, size = keySize) }
            handlers(MfdKey.RANGE_OUT).let { (d, u) -> LabelKey("−", d, u, size = keySize) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            handlers(MfdKey.MENU).let { (d, u) -> LabelKey("Menu", d, u, size = keySize) }
            handlers(MfdKey.HOME).let { (d, u) -> LabelKey("Home", d, u, size = keySize) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            handlers(MfdKey.BACK).let { (d, u) -> LabelKey("Back", d, u, size = keySize) }
            handlers(MfdKey.SWITCH).let { (d, u) -> LabelKey("Pane", d, u, size = keySize) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            handlers(MfdKey.WPT).let { (d, u) -> LabelKey("WPT", d, u, size = keySize) }
            Box(Modifier.width(keySize))
        }
    }
}
