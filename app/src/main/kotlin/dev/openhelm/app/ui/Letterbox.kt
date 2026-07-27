package dev.openhelm.app.ui

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** The display's picture is 800×480. */
const val VIDEO_ASPECT: Float = 5f / 3f

/**
 * Fit a 5:3 picture inside a container of [maxWidth] × [maxHeight] without distorting it and
 * **without overflowing** — pillarboxed when the container is wider than 5:3, letterboxed when it
 * is taller.
 *
 * The obvious spelling, `Modifier.fillMaxSize().aspectRatio(5f/3f)`, is a trap: `fillMaxSize`
 * hands `aspectRatio` exact constraints, leaving it no freedom to shrink, so in a wide, short
 * window it computes its height from the full width and silently draws *outside* its parent. On a
 * phone in landscape that meant the video spilled upward across the status bar and its buttons —
 * found by measuring the laid-out bounds, which were correct, against a screenshot, which was not.
 *
 * Choosing the axis to fill explicitly is what makes it safe at every window shape.
 */
fun letterboxModifier(maxWidth: Dp, maxHeight: Dp): Modifier {
    val containerAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else VIDEO_ASPECT
    return if (containerAspect > VIDEO_ASPECT) {
        // Container is relatively wider: height is the limit, bars go left and right.
        Modifier.fillMaxHeight().aspectRatio(VIDEO_ASPECT)
    } else {
        // Container is relatively taller: width is the limit, bars go top and bottom.
        Modifier.fillMaxWidth().aspectRatio(VIDEO_ASPECT)
    }
}
