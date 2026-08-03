package dev.openhelm.app.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The two tactile events the remote produces, at the strongest constants the device offers.
 *
 * The defaults were `VIRTUAL_KEY` for a press and `CLOCK_TICK` for a dial detent, which are the
 * lightest things the platform defines — they are meant for a keyboard in a quiet room, and they
 * are close to imperceptible through a glove, on a wet screen, or with an engine running. Everything
 * here is one step up from that.
 *
 * Deliberately still routed through [View.performHapticFeedback] rather than driving the vibrator
 * directly. That keeps the app inside the user's own haptics setting — someone who has turned system
 * haptics off has done so on purpose — and needs no `VIBRATE` permission. The cost is that strength
 * is chosen from a fixed vocabulary rather than set as an amplitude; if these still read as too
 * light on real hardware, the next step is an explicit `VibrationEffect`, which does need the
 * permission.
 */
internal object HelmHaptics {

    /**
     * A key, dial sector or hub going down.
     *
     * `CONFIRM` where it exists: it is the platform's "this registered" effect and is distinctly
     * heavier than `VIRTUAL_KEY`. `LONG_PRESS` is the closest older equivalent — the name describes
     * the gesture it was designed for, not its weight, and it is the strongest constant available
     * before API 30.
     */
    fun keyDown(view: View) {
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(effect)
    }

    /**
     * A finger landing on the picture, where the touch is forwarded to the display.
     *
     * Same weight as a key press, and for the same reason: this *is* a press — it puts the
     * display's cursor somewhere. The display's own response comes back over video a moment
     * later, so until then the buzz is the only confirmation the app took the touch at all.
     */
    fun touchDown(view: View) = keyDown(view)

    /**
     * A step of a drag or a pinch across the picture.
     *
     * These fire repeatedly while a finger moves, so this is the light tick rather than the press
     * weight — the same reasoning as the dial's detents. Callers throttle it; a tick per pointer
     * event would be a continuous buzz that says nothing.
     */
    fun gestureStep(view: View) = detent(view)

    /**
     * One detent of the rotary ring.
     *
     * These fire repeatedly while the ring turns, so this stays lighter than [keyDown]: a full
     * press-weight thump on every click of a sweep is unpleasant and blurs into a buzz. `VIRTUAL_KEY`
     * is still a clear step up from the `CLOCK_TICK` this used to use, and it stays crisp enough to
     * be counted.
     */
    fun detent(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}
