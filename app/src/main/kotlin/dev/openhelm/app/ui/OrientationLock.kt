package dev.openhelm.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewTreeObserver
import android.view.WindowManager
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
 * Keeps the screen awake for as long as the caller stays composed.
 *
 * This belongs to the *session*, not to the video. It used to be a `keepScreenOn = true` on the
 * `TextureView`, which meant the screen slept the moment the user switched to the full-screen
 * keypad — the video pane and its flag left the composition together. Someone steering with the
 * keypad has the same reason not to want the display blanking as someone watching the chart, and
 * a mounted phone gets few touches to keep it awake by itself.
 *
 * The flag is cleared on dispose rather than left set, so leaving the session hands the normal
 * screen timeout back and the app does not quietly hold the display on in the background.
 */
@Composable
fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * Hides the system bars for as long as the caller stays composed, restoring them on the way out.
 *
 * A mounted phone showing a chart wants the whole panel: the gesture pill otherwise sits over the
 * bottom edge of the video, and the status bar steals height that the 5:3 picture needs. Bars stay
 * swipe-reachable (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`) rather than being locked away, so
 * nothing traps the user.
 *
 * **Hiding once is not enough**, which is the bug this used to have. The request is not a mode the
 * window stays in: anything that takes window focus puts the bars back and leaves them there — the
 * notification shade, the recents switcher, a permission dialog, and, the case this was reported
 * from, a phone call. The bars then sit on top of the remote's own status row, because transient
 * bars are an overlay and contribute no layout inset, so the row underneath is unreadable rather
 * than merely displaced.
 *
 * So the hide is re-applied every time the window regains focus. Focus is the right trigger and a
 * swipe is not: a swipe is the user asking for the bars, and re-hiding on that would take away the
 * escape hatch this deliberately leaves open.
 */
@Composable
fun ImmersiveWhileConnected() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view, context) {
        val window = (context as? Activity)?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowInsetsControllerCompat(window, view)

            fun hideBars() {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }

            hideBars()

            val onFocus = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) hideBars()
            }
            view.viewTreeObserver.addOnWindowFocusChangeListener(onFocus)

            onDispose {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(onFocus)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
