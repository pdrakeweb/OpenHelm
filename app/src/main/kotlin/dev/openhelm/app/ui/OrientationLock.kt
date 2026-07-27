package dev.openhelm.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Locks the window to landscape for as long as the caller stays composed, and releases the lock
 * back to the platform default the moment it leaves. The remote and simulated-remote screens are
 * landscape-only by design: the side-by-side layout needs the width, and this is a phone mounted
 * at a helm, not held in the hand — while the connect flow stays free to follow whatever rotation
 * the device itself is set to.
 *
 * `SENSOR_LANDSCAPE` rather than a single fixed orientation, so a phone mounted upside-down still
 * shows right-side-up video instead of being locked to the wrong landscape.
 *
 * Safe with the `configChanges="orientation|..."` declared on the activity — the window rotates
 * without the activity recreating, so the connection and the simulated-video animation are
 * untouched by entering or leaving this lock.
 */
@Composable
fun LockLandscape() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/**
 * Hides the system bars for as long as the caller stays composed, restoring them on the way out.
 *
 * A mounted phone showing a chart wants the whole panel: the gesture pill otherwise sits over the
 * bottom edge of the video, and the status bar steals height that the 5:3 picture needs. Bars stay
 * swipe-reachable (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`) rather than being locked away, so
 * nothing traps the user.
 */
@Composable
fun ImmersiveWhileConnected() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowInsetsControllerCompat(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}
