package dev.openhelm.app.ambient

import dev.openhelm.app.ui.HelmPalette
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sun altitude, and what it means for the display's colour mode, for when there is no ambient
 * light sensor to ask directly — see [AmbientPaletteMonitor], which is the only caller.
 *
 * **Not navigation-grade**, deliberately: there is no atmospheric refraction correction, and the
 * hour angle is derived from the clock hour rather than true solar time, which would need
 * longitude and the equation of time — inputs this app does not collect (see
 * [DEFAULT_LATITUDE_DEGREES]'s doc for why location is not collected either). The error this
 * introduces against the true civil/nautical twilight instant is at most tens of minutes, which is
 * well inside the band a colour-mode switch can tolerate — this is a fallback for when the far more
 * direct signal, the light sensor, is unavailable, not the primary mechanism.
 *
 * Pure and Android-free so the day/dusk/night boundaries are checkable on the JVM without a device
 * or Robolectric — see `TwilightTest`.
 */
internal object Twilight {
    /**
     * Northern mid-latitudes: the assumption used whenever an actual latitude is not supplied.
     *
     * OpenHelm does not request a location permission — this fallback exists only to stand in for
     * the light sensor, and asking for location to sharpen a fallback-of-a-fallback is not a trade
     * worth a permission prompt. "Assume Northern Hemisphere" is the explicit fallback rule; a
     * mid-latitude within it (roughly the US/European coastal band) keeps the twilight band width
     * realistic rather than picking an equatorial or polar extreme.
     */
    const val DEFAULT_LATITUDE_DEGREES = 40.0

    /** Below this sun altitude, sunlight itself is no longer the dominant light source. */
    private const val CIVIL_TWILIGHT_DEGREES = -6.0

    /** Below this, the sky itself contributes no usable light — full night. */
    private const val NAUTICAL_TWILIGHT_DEGREES = -12.0

    /**
     * The sun's altitude above the horizon in degrees (negative once it has set), for [at] at
     * [latitudeDegrees].
     */
    fun sunAltitudeDegrees(at: ZonedDateTime, latitudeDegrees: Double): Double {
        val declinationRad = Math.toRadians(23.44) * sin(2 * PI / 365.0 * (at.dayOfYear - 81))
        val hourOfDay = at.hour + at.minute / 60.0 + at.second / 3600.0
        val hourAngleRad = Math.toRadians(15.0 * (hourOfDay - 12.0))
        val latRad = Math.toRadians(latitudeDegrees)
        val sinAltitude = sin(latRad) * sin(declinationRad) +
            cos(latRad) * cos(declinationRad) * cos(hourAngleRad)
        return Math.toDegrees(asin(sinAltitude.coerceIn(-1.0, 1.0)))
    }

    /**
     * Day ([HelmPalette.HIGH_CONTRAST]), dusk ([HelmPalette.DARK] — between civil and nautical
     * twilight) or full night ([HelmPalette.NIGHT] — past nautical twilight), for [at] at
     * [latitudeDegrees].
     */
    fun classify(at: ZonedDateTime, latitudeDegrees: Double = DEFAULT_LATITUDE_DEGREES): HelmPalette {
        val altitude = sunAltitudeDegrees(at, latitudeDegrees)
        return when {
            altitude >= CIVIL_TWILIGHT_DEGREES -> HelmPalette.HIGH_CONTRAST
            altitude >= NAUTICAL_TWILIGHT_DEGREES -> HelmPalette.DARK
            else -> HelmPalette.NIGHT
        }
    }
}
