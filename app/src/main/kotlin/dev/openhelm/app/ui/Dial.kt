package dev.openhelm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import dev.openhelm.protocol.MfdKey
import kotlin.math.atan2
import kotlin.math.hypot

/** Regions of the dial, for pressed-state drawing. */
private enum class DialRegion { NONE, OK, UP, DOWN, LEFT, RIGHT, RING }

/**
 * The composite dial: OK in the hub, four direction sectors around it, and a rotary outer ring.
 * The MFD's own front panel has exactly this control, which is why the remote has it — muscle
 * memory carries over. Entirely drawn here; no artwork.
 *
 * Direction sectors follow the keypad rules: DOWN on finger contact, UP on release or
 * cancellation, never a synthetic release — a held sector sweeps the cursor via the display's own
 * auto-repeat.
 *
 * The ring turns rotation into discrete zoom steps ([onRotate], one call per [STEP_DEGREES]).
 * Range keys remain on the panel as the well-understood path; the ring is the fast, eyes-on-the-
 * water way to do the same thing.
 */
@Composable
fun Dial(
    onKeyDown: (MfdKey) -> Unit,
    onKeyUp: (MfdKey) -> Unit,
    onRotate: (step: Int, accumulated: Int) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 141.dp,
) {
    var pressed by remember { mutableStateOf(DialRegion.NONE) }
    val view = LocalView.current
    val textMeasurer = rememberTextMeasurer()

    val okColor = MaterialTheme.colorScheme.surfaceVariant
    val okPressedColor = MaterialTheme.colorScheme.primary
    val ringColor = MaterialTheme.colorScheme.surfaceVariant
    val arrowColor = MaterialTheme.colorScheme.onSurface
    val arrowPressedColor = MaterialTheme.colorScheme.onPrimary
    val sectorPressedColor = MaterialTheme.colorScheme.primary
    val okTextStyle = TextStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
    )

    Box(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    val radius = this.size.width / 2f
                    val rel = down.position - center
                    val dist = hypot(rel.x, rel.y)

                    when {
                        dist <= radius * OK_RADIUS -> {
                            pressed = DialRegion.OK
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onKeyDown(MfdKey.OK)
                            waitAllUp()
                            pressed = DialRegion.NONE
                            onKeyUp(MfdKey.OK)
                        }

                        dist <= radius * SECTOR_RADIUS -> {
                            val (region, key) = directionAt(rel)
                            pressed = region
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onKeyDown(key)
                            waitAllUp()
                            pressed = DialRegion.NONE
                            onKeyUp(key)
                        }

                        else -> {
                            // The ring: turn angle travel into discrete steps.
                            pressed = DialRegion.RING
                            var lastAngle = Math.toDegrees(atan2(rel.y, rel.x).toDouble())
                            var travel = 0.0
                            var accumulated = 0
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                if (change == null || !change.pressed) break
                                val p = change.position - center
                                val angle = Math.toDegrees(atan2(p.y, p.x).toDouble())
                                var delta = angle - lastAngle
                                while (delta > 180) delta -= 360
                                while (delta < -180) delta += 360
                                lastAngle = angle
                                travel += delta
                                while (travel >= STEP_DEGREES) {
                                    travel -= STEP_DEGREES
                                    accumulated++
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onRotate(1, accumulated)
                                }
                                while (travel <= -STEP_DEGREES) {
                                    travel += STEP_DEGREES
                                    accumulated--
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onRotate(-1, accumulated)
                                }
                                change.consume()
                            }
                            pressed = DialRegion.NONE
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.size(size)) {
            val r = this.size.width / 2f
            val c = Offset(r, r)

            // Outer ring, with tick marks so rotation reads as rotation.
            drawCircle(
                color = if (pressed == DialRegion.RING) sectorPressedColor else ringColor,
                radius = r * 0.92f,
                center = c,
                style = Stroke(width = r * 0.14f),
            )
            repeat(12) { i ->
                rotate(degrees = i * 30f, pivot = c) {
                    drawLine(
                        color = arrowColor.copy(alpha = 0.5f),
                        start = Offset(c.x, c.y - r * 0.98f),
                        end = Offset(c.x, c.y - r * 0.86f),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }

            // Direction sector backgrounds when pressed.
            if (pressed in listOf(DialRegion.UP, DialRegion.DOWN, DialRegion.LEFT, DialRegion.RIGHT)) {
                val startAngle = when (pressed) {
                    DialRegion.RIGHT -> -45f
                    DialRegion.DOWN -> 45f
                    DialRegion.LEFT -> 135f
                    else -> 225f
                }
                drawArc(
                    color = sectorPressedColor,
                    startAngle = startAngle,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = Offset(c.x - r * SECTOR_RADIUS, c.y - r * SECTOR_RADIUS),
                    size = androidx.compose.ui.geometry.Size(r * SECTOR_RADIUS * 2, r * SECTOR_RADIUS * 2),
                )
            }

            // Direction arrows.
            val arrowDist = r * 0.60f
            val arrowHalf = r * 0.09f
            fun arrow(angleDeg: Float, region: DialRegion) {
                rotate(degrees = angleDeg, pivot = c) {
                    val tip = Offset(c.x, c.y - arrowDist - arrowHalf)
                    val path = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(tip.x - arrowHalf * 1.2f, tip.y + arrowHalf * 2)
                        lineTo(tip.x + arrowHalf * 1.2f, tip.y + arrowHalf * 2)
                        close()
                    }
                    drawPath(path, if (pressed == region) arrowPressedColor else arrowColor)
                }
            }
            arrow(0f, DialRegion.UP)
            arrow(90f, DialRegion.RIGHT)
            arrow(180f, DialRegion.DOWN)
            arrow(270f, DialRegion.LEFT)

            // OK hub.
            drawCircle(
                color = if (pressed == DialRegion.OK) okPressedColor else okColor,
                radius = r * OK_RADIUS,
                center = c,
            )
            val label = textMeasurer.measure("OK", okTextStyle)
            drawText(
                label,
                topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f),
            )
        }
    }
}

/** Suspend until every pointer is up (or the gesture is cancelled). */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitAllUp() {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) return
    }
}

private fun directionAt(rel: Offset): Pair<DialRegion, MfdKey> {
    val angle = Math.toDegrees(atan2(rel.y, rel.x).toDouble())
    return when {
        angle >= -45 && angle < 45 -> DialRegion.RIGHT to MfdKey.RIGHT
        angle >= 45 && angle < 135 -> DialRegion.DOWN to MfdKey.DOWN
        angle >= -135 && angle < -45 -> DialRegion.UP to MfdKey.UP
        else -> DialRegion.LEFT to MfdKey.LEFT
    }
}

private const val OK_RADIUS = 0.32f
private const val SECTOR_RADIUS = 0.78f
private const val STEP_DEGREES = 20.0
