package dev.openhelm.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.IntSize

/** The moments in a gesture worth feeling. Weighted differently: see [HelmHaptics]. */
internal enum class HapticMoment { TOUCH_DOWN, STEP }

/** What the gesture is doing right now, for anything that wants to draw it. */
internal sealed interface VideoGesture {
    /** One finger on the picture. [moving] is false for the initial contact. */
    data class Touch(val position: Offset, val moving: Boolean) : VideoGesture

    /** Two or more fingers: a local zoom, which never reaches the display. */
    data class Pinch(val positions: List<Offset>, val scale: Float) : VideoGesture

    /** Every finger lifted, or the gesture cancelled. */
    data object End : VideoGesture
}

/**
 * The picture's gesture handling, in one place.
 *
 * Extracted so the real pane and the simulated one run the **same** code. Simulation exists to show
 * what ships, and a second implementation of the one gesture on this screen that must not misbehave
 * — a pinch that leaks a tap to a chartplotter, a cancellation that leaves a finger logically down
 * — would be the most expensive place in the app to have two of anything.
 *
 * - **One finger** talks to the display: [onDown] / [onMove] / [onUp], in view pixels.
 * - **Two fingers** zoom and pan the local view via [onTransform], and are never forwarded.
 *
 * [onGesture] is a passive observer for drawing; it is given raw view positions and is free to do
 * nothing, which is what the real pane does with it.
 */
internal suspend fun PointerInputScope.videoTouchGestures(
    scaleOf: () -> Float,
    panOf: () -> Offset,
    onTransform: (scale: Float, pan: Offset) -> Unit,
    onDown: (x: Float, y: Float, size: IntSize) -> Unit,
    onMove: (x: Float, y: Float, size: IntSize) -> Unit,
    onUp: (x: Float, y: Float, size: IntSize) -> Unit,
    onGesture: (VideoGesture) -> Unit = {},
    /**
     * Fired when the app *accepts* a gesture step, for haptic confirmation. Deliberately driven
     * from the same points that decide to send — a buzz on a touch the app then withheld would be
     * a lie, and on this screen the felt response is the only feedback that arrives before the
     * display's own picture catches up.
     */
    onHaptic: (HapticMoment) -> Unit = {},
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val viewSize = this.size
        var touching = true
        var pinching = false
        // Timing comes from the pointer events' own uptime clock, not the wall clock: wall time
        // jumps with NTP corrections and manual changes, and a backwards jump mid-gesture would
        // re-open the pinch grace window or stall the move throttle.
        var lastMs = 0L

        fun content(p: Offset): Offset {
            // Invert the local view transform: coordinates handed on must describe the *chart*
            // under the finger, not the untransformed position.
            val scale = scaleOf()
            val pan = panOf()
            val cx = viewSize.width / 2f
            val cy = viewSize.height / 2f
            return Offset(
                ((p.x - pan.x - cx) / scale + cx).coerceIn(0f, viewSize.width.toFloat()),
                ((p.y - pan.y - cy) / scale + cy).coerceIn(0f, viewSize.height.toFloat()),
            )
        }

        var last = content(down.position)
        val downAtMs = down.uptimeMillis
        // The DOWN is deliberately NOT sent yet. A pinch begins as a single finger, so sending on
        // first contact meant every two-finger zoom emitted a DOWN and then an UP — a real tap on
        // the chart, which is exactly what this gesture's contract says a pinch must never do. The
        // commit is deferred until the gesture has proved it is single-finger by surviving
        // PINCH_GRACE_MS, or until the finger lifts (a quick tap, emitted whole on release).
        var downSent = false
        fun sendDownOnce() {
            if (!downSent) {
                downSent = true
                onDown(last.x, last.y, viewSize)
                onHaptic(HapticMoment.TOUCH_DOWN)
            }
        }

        // Throttles the drag/pinch tick. A tick per pointer event is a continuous buzz that
        // conveys nothing; one every MOVE_INTERVAL_MS reads as movement being tracked.
        var lastHapticMs = 0L
        fun stepHaptic(now: Long) {
            if (now - lastHapticMs >= HAPTIC_INTERVAL_MS) {
                lastHapticMs = now
                onHaptic(HapticMoment.STEP)
            }
        }

        onGesture(VideoGesture.Touch(down.position, moving = false))

        try {
            while (true) {
                val event = awaitPointerEvent()
                val pressedChanges = event.changes.filter { it.pressed }

                if (pressedChanges.size >= 2) {
                    if (touching) {
                        // Second finger: this is a local zoom, not a conversation with the
                        // display. Retract the DOWN only if the grace window already committed one.
                        if (downSent) {
                            onUp(last.x, last.y, viewSize)
                            downSent = false
                        }
                        touching = false
                        pinching = true
                    }
                    val scale = (scaleOf() * event.calculateZoom()).coerceIn(1f, MAX_VIDEO_ZOOM)
                    val panChange = event.calculatePan()
                    val maxX = (scale - 1f) * viewSize.width / 2f
                    val maxY = (scale - 1f) * viewSize.height / 2f
                    val pan = panOf()
                    onTransform(
                        scale,
                        Offset(
                            (pan.x + panChange.x).coerceIn(-maxX, maxX),
                            (pan.y + panChange.y).coerceIn(-maxY, maxY),
                        ),
                    )
                    onGesture(VideoGesture.Pinch(pressedChanges.map { it.position }, scale))
                    stepHaptic(event.changes.first().uptimeMillis)
                    event.changes.forEach { it.consume() }
                } else if (pressedChanges.size == 1 && touching) {
                    val change = pressedChanges.first()
                    val now = change.uptimeMillis
                    if (now - downAtMs >= PINCH_GRACE_MS) {
                        sendDownOnce()
                        if (now - lastMs >= MOVE_INTERVAL_MS) {
                            lastMs = now
                            last = content(change.position)
                            onMove(last.x, last.y, viewSize)
                            onGesture(VideoGesture.Touch(change.position, moving = true))
                            stepHaptic(now)
                        }
                    }
                    change.consume()
                } else if (pressedChanges.isEmpty()) {
                    if (touching) {
                        // A tap shorter than the grace window never committed a DOWN; emit the
                        // whole DOWN+UP here so a quick tap still places the cursor.
                        sendDownOnce()
                        onUp(last.x, last.y, viewSize)
                        downSent = false
                    }
                    if (pinching && scaleOf() <= SNAP_BACK_BELOW) {
                        // Near-1x is an accident of finger lift; snap clean.
                        onTransform(1f, Offset.Zero)
                    }
                    break
                }
                // One finger remaining after a pinch: ignored until lift — a half-ended pinch must
                // not start sending chart touches.
            }
        } finally {
            // However the gesture ends — including a cancellation from an edge swipe or the pane
            // leaving composition — the display must not be left believing a finger is down.
            if (downSent) onUp(last.x, last.y, viewSize)
            onGesture(VideoGesture.End)
        }
    }
}

internal const val MAX_VIDEO_ZOOM = 4f
internal const val SNAP_BACK_BELOW = 1.1f
internal const val MOVE_INTERVAL_MS = 33L

/**
 * Minimum gap between drag/pinch ticks.
 *
 * Longer than [MOVE_INTERVAL_MS] on purpose: moves are sent at ~30 Hz to keep the display's
 * cursor smooth, but a haptic at 30 Hz is not thirty taps, it is a hum. ~7 Hz is fast enough to
 * feel continuous with the finger and slow enough that each tick is a distinct event.
 */
internal const val HAPTIC_INTERVAL_MS = 140L

/**
 * How long a single finger must stay down before its touch is forwarded to the display.
 *
 * Two fingers of a pinch do not land in the same event — the second trails the first by a
 * human-scale interval. This is the window in which the gesture is still allowed to turn out to be
 * a pinch. Long enough to cover an ordinary two-finger landing, short enough to be invisible at the
 * start of a drag, which is throttled to [MOVE_INTERVAL_MS] anyway. A tap that ends inside the
 * window is not lost: it is sent whole, DOWN and UP together, when the finger lifts.
 */
internal const val PINCH_GRACE_MS = 70L
