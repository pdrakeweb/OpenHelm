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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.min

/** Regions of the dial, for pressed-state drawing. */
internal enum class DialRegion { NONE, OK, UP, DOWN, LEFT, RIGHT, RING }

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
    size: Dp = 168.dp,
) {
    // Never shrink below the size at which the hub stops being a legal touch target, whatever a
    // caller asks for. The panel around this scrolls, so a dial that refuses to shrink pushes
    // content into a scroll rather than into an unhittable control.
    val dialSize = size.coerceAtLeast(MinDialSize)
    var pressed by remember { mutableStateOf(DialRegion.NONE) }
    val view = LocalView.current
    val textMeasurer = rememberTextMeasurer()

    // Same source as the keypad keys, so the dial and the keys press the same colour.
    val controls = LocalHelmControls.current
    val okColor = controls.keyFill
    val okPressedColor = controls.keyPressedFill
    val ringColor = controls.keyFill
    val arrowColor = controls.keyContent
    val arrowPressedColor = controls.keyPressedContent
    val sectorPressedColor = controls.keyPressedFill
    val okTextStyle = TextStyle(
        color = controls.keyContent,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
    )

    // A press-and-release pair, for the assistive path only. A screen-reader user cannot express
    // "hold", and a DOWN left open would auto-repeat on the display until something else closed it.
    fun tap(key: MfdKey) {
        onKeyDown(key)
        onKeyUp(key)
    }

    Box(
        modifier = modifier
            .size(dialSize)
            // Without this the dial was, to the framework, an unlabelled Box with a raw
            // pointerInput: invisible to TalkBack, and absent from a uiautomator dump — which is
            // why the touch-target sweep in tests/14 could not see the largest control on the
            // panel. The five commands are exposed as custom actions rather than as five child
            // nodes, because they are regions of one drawn shape and have no separate bounds to
            // report; zoom is offered here too, since rotating a ring is not a gesture an
            // assistive user can perform at all.
            .semantics {
                role = Role.Button
                contentDescription = "Cursor dial"
                onClick(label = "Confirm") { tap(MfdKey.OK); true }
                customActions = listOf(
                    CustomAccessibilityAction("Move the cursor up") { tap(MfdKey.UP); true },
                    CustomAccessibilityAction("Move the cursor down") { tap(MfdKey.DOWN); true },
                    CustomAccessibilityAction("Move the cursor left") { tap(MfdKey.LEFT); true },
                    CustomAccessibilityAction("Move the cursor right") { tap(MfdKey.RIGHT); true },
                    CustomAccessibilityAction("Zoom in") { onRotate(1, 1); true },
                    CustomAccessibilityAction("Zoom out") { onRotate(-1, -1); true },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    // Radius from the SHORTER axis, centre from the true centre — identical to
                    // the Canvas below. These used to disagree (`Offset(r, r)` when drawing,
                    // `Offset(w/2, h/2)` when hit-testing), which is harmless while the box is
                    // square and a real hazard the moment it is not: at the compact band's
                    // 140×156 the drawn dial sat 8dp above the tappable one, so a tap on the
                    // visible OK hub registered as UP and sent a cursor command.
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    val radius = min(this.size.width, this.size.height) / 2f
                    val rel = down.position - center
                    val dist = hypot(rel.x, rel.y)

                    when {
                        dist <= radius * OK_RADIUS -> pressAndHold(MfdKey.OK, DialRegion.OK, view, onKeyDown, onKeyUp) {
                            pressed = it
                        }

                        dist <= radius * SECTOR_RADIUS -> {
                            val (region, key) = directionAt(rel)
                            pressAndHold(key, region, view, onKeyDown, onKeyUp) { pressed = it }
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
        Canvas(Modifier.size(dialSize)) {
            // Must match the hit-test above exactly — see the comment there.
            val r = min(this.size.width, this.size.height) / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)

            // The dial's body, filled.
            //
            // This used to be transparent: only the ring was stroked, so the direction arrows were
            // drawn straight onto whatever was behind the panel. That survived two dark palettes by
            // luck — light arrows on a dark page — and vanished completely in high contrast, where
            // the page is white and so are the arrows. A filled body also makes the sectors look
            // like the targets they are, instead of empty space around a ring.
            drawCircle(color = okColor, radius = r * SECTOR_RADIUS, center = c)

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

            // A + and a − on the ring itself. Without these the rotate-to-zoom gesture is
            // invisible: nothing on screen said the ring did anything, so it read as decoration.
            val markR = r * 0.92f
            val markHalf = r * 0.055f
            val markStroke = 2.dp.toPx()
            val markColor = arrowColor.copy(alpha = 0.8f)
            // Plus, on the right of the ring — the direction that zooms in.
            drawLine(
                color = markColor,
                start = Offset(c.x + markR - markHalf, c.y),
                end = Offset(c.x + markR + markHalf, c.y),
                strokeWidth = markStroke,
            )
            drawLine(
                color = markColor,
                start = Offset(c.x + markR, c.y - markHalf),
                end = Offset(c.x + markR, c.y + markHalf),
                strokeWidth = markStroke,
            )
            // Minus, on the left.
            drawLine(
                color = markColor,
                start = Offset(c.x - markR - markHalf, c.y),
                end = Offset(c.x - markR + markHalf, c.y),
                strokeWidth = markStroke,
            )

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

            // OK hub. Same fill as the body it sits in, so it needs an outline to read as a
            // separate target rather than as the middle of one large button.
            drawCircle(
                color = if (pressed == DialRegion.OK) okPressedColor else okColor,
                radius = r * OK_RADIUS,
                center = c,
            )
            drawCircle(
                color = arrowColor,
                radius = r * OK_RADIUS,
                center = c,
                style = Stroke(width = 2.dp.toPx()),
            )
            val label = textMeasurer.measure("OK", okTextStyle)
            drawText(
                label,
                topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f),
            )
        }
    }
}

/**
 * Hold [key] down for as long as the finger stays on the dial, and release it **no matter how the
 * gesture ends**.
 *
 * The `finally` is the whole point and is not defensive padding. `awaitEachGesture` abandons its
 * block with a cancellation when the pointer stream is cancelled — a swipe from the screen edge to
 * pull the system bars back, the composable leaving composition mid-press, an activity recreation
 * for a configuration this activity does not declare. Without the `finally` the release is simply
 * skipped, and because the display implements auto-repeat itself, a key it believes is still held
 * repeats **forever**: the cursor runs away and the chart pans off, with no further input from the
 * user. This mirrors [MfdKeyButton], which has always guarded it; the dial did not.
 */
private suspend inline fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.pressAndHold(
    key: MfdKey,
    region: DialRegion,
    view: android.view.View,
    onKeyDown: (MfdKey) -> Unit,
    onKeyUp: (MfdKey) -> Unit,
    setPressed: (DialRegion) -> Unit,
) {
    setPressed(region)
    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    onKeyDown(key)
    try {
        waitAllUp()
    } finally {
        setPressed(DialRegion.NONE)
        onKeyUp(key)
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

internal fun directionAt(rel: Offset): Pair<DialRegion, MfdKey> {
    val angle = Math.toDegrees(atan2(rel.y, rel.x).toDouble())
    return when {
        angle >= -45 && angle < 45 -> DialRegion.RIGHT to MfdKey.RIGHT
        angle >= 45 && angle < 135 -> DialRegion.DOWN to MfdKey.DOWN
        angle >= -135 && angle < -45 -> DialRegion.UP to MfdKey.UP
        else -> DialRegion.LEFT to MfdKey.LEFT
    }
}

/**
 * The dial never renders smaller than this: at exactly this size the OK hub is a full
 * [MinHelmTarget] across (`156 × 0.36 ≈ 56`).
 *
 * **Honest measurements at this size** (r = 78dp), against the project's own [MinHelmTarget] of
 * 56dp — not Material's 48dp, which this app deliberately exceeds:
 *
 * | Region | Extent | Clears 56dp? |
 * |---|---|---|
 * | OK hub | 56dp across | yes, exactly |
 * | Direction sector | 33dp radial × 44–96dp tangential | **no** — radially |
 * | Rotary ring | 17dp radial band | **no** |
 *
 * So two of the three regions are under the bar, and saying otherwise would be the kind of claim
 * this file exists to stop making. The trade is deliberate: widening either would swallow the hub
 * or make the control too tall for a phone in landscape, a sector is aimed outward from a large
 * centre rather than pinpointed, and every one of these commands is also on the full-screen keypad
 * at full size. It remains a trade, not compliance, and it wants re-measuring on real hardware
 * with wet or gloved hands.
 */
val MinDialSize: Dp = 156.dp

/**
 * Hub radius as a fraction of the dial's. Sized so that at [MinDialSize] the hub is a full
 * [MinHelmTarget] across.
 */
private const val OK_RADIUS = 0.36f
private const val SECTOR_RADIUS = 0.78f
private const val STEP_DEGREES = 20.0
