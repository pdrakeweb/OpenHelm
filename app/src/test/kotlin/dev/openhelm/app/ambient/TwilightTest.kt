package dev.openhelm.app.ambient

import dev.openhelm.app.ui.HelmPalette
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sun-altitude fallback used when there is no ambient light sensor (or it is currently
 * unreliable) — see [AmbientPaletteMonitor]. Checked against a mid-latitude Northern-Hemisphere
 * summer day, where noon, sunset-ish evening and the middle of the night are all unambiguous.
 */
class TwilightTest {

    private val summerSolstice = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `local noon is day, at any reasonable latitude`() {
        assertEquals(
            HelmPalette.HIGH_CONTRAST,
            Twilight.classify(summerSolstice, latitudeDegrees = Twilight.DEFAULT_LATITUDE_DEGREES),
        )
    }

    @Test
    fun `the middle of the night is full night`() {
        val midnight = summerSolstice.withHour(0)
        assertEquals(
            HelmPalette.NIGHT,
            Twilight.classify(midnight, latitudeDegrees = Twilight.DEFAULT_LATITUDE_DEGREES),
        )
    }

    @Test
    fun `there is a dusk band between noon and the middle of the night`() {
        // Sweeping from noon to midnight at 40N in June in fine steps, the sun crosses the civil
        // and nautical twilight boundaries in the evening — somewhere in that sweep the
        // classification must be DARK, not a straight jump from HIGH_CONTRAST to NIGHT. The band
        // is narrow enough at this latitude and date that hourly steps can miss it; minutes do not.
        val sawDusk = (0..720 step 5).map { minutes ->
            Twilight.classify(summerSolstice.plusMinutes(minutes.toLong()), Twilight.DEFAULT_LATITUDE_DEGREES)
        }.any { it == HelmPalette.DARK }
        assert(sawDusk) { "expected a DARK (dusk) reading somewhere between noon and midnight" }
    }

    @Test
    fun `far enough south of the sun, even local noon is night`() {
        // On the June solstice the sun never rises much above the horizon deep in the Southern
        // Hemisphere — this is the sanity check that latitude, not just hour, drives the result.
        assertEquals(
            HelmPalette.NIGHT,
            Twilight.classify(summerSolstice, latitudeDegrees = -80.0),
        )
    }
}
