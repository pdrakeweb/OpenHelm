package dev.openhelm.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** A finger contact, remembered long enough to fade. */
internal data class TouchMark(
    val position: Offset,
    val pinch: Boolean,
    val bornNanos: Long,
)

/**
 * Fading marks showing where fingers went on the simulated picture.
 *
 * Simulation shows a chart that cannot respond, so without this a tap, a drag and a pinch all look
 * identical: nothing happens. The marks are what make the gesture legible — you can see that the
 * drag was registered as a drag and where it went, which is the only way to tell the touch handling
 * is working when there is no display at the other end to move a cursor.
 *
 * Sim-only on purpose. A real session has the display's own cursor as the feedback, and drawing
 * over live video would be adding decoration to the one surface that has to stay trustworthy.
 */
internal class TouchMarkTrail {
    private val marks = mutableListOf<TouchMark>()

    fun add(position: Offset, pinch: Boolean, nowNanos: Long) {
        marks += TouchMark(position, pinch, nowNanos)
        // A drag adds a mark per move; the tail is bounded so a long sweep cannot grow without end.
        if (marks.size > MaxMarks) marks.subList(0, marks.size - MaxMarks).clear()
    }

    fun prune(nowNanos: Long) {
        marks.removeAll { (nowNanos - it.bornNanos) / 1_000_000_000f > MarkFadeSeconds }
    }

    fun isEmpty(): Boolean = marks.isEmpty()

    fun draw(scope: DrawScope, nowNanos: Long, color: Color) = with(scope) {
        marks.forEach { mark ->
            val age = (nowNanos - mark.bornNanos) / 1_000_000_000f
            val life = (1f - age / MarkFadeSeconds).coerceIn(0f, 1f)
            if (life <= 0f) return@forEach
            // Grows as it fades, like a ripple, so a stationary tap still reads as an event rather
            // than as a dot that was always there.
            val radius = (if (mark.pinch) 26f else 18f) * (1f + (1f - life) * 0.9f)
            drawCircle(
                color = color.copy(alpha = 0.85f * life),
                radius = radius,
                center = mark.position,
                style = Stroke(width = 3f * life + 1f),
            )
            drawCircle(
                color = color.copy(alpha = 0.22f * life),
                radius = radius,
                center = mark.position,
            )
        }
    }

    private companion object {
        const val MaxMarks = 48
        const val MarkFadeSeconds = 0.9f
    }
}

/**
 * Drives [trail]'s fade, and returns a value that changes every frame while anything is still
 * visible, so a `Canvas` reading it redraws.
 */
@Composable
internal fun rememberTouchMarkClock(trail: TouchMarkTrail, active: Boolean): Long {
    var clock by remember { mutableLongStateOf(0L) }

    LaunchedEffect(active) {
        // Runs while a finger is down and keeps going until the last mark has faded, so lifting
        // off does not snap the trail away.
        while (active || !trail.isEmpty()) {
            withFrameNanos { now ->
                trail.prune(now)
                clock = now
            }
        }
        // A final frame with nothing left, so the last mark is actually cleared from the screen.
        withFrameNanos { now -> clock = now }
    }
    return clock
}
