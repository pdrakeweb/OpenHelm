package dev.openhelm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The age shown over a held picture — the number that says how far to trust it. */
class PictureAgeTest {

    @Test
    fun `seconds, then minutes and seconds, then hours and minutes`() {
        assertEquals("0 s", formatPictureAge(0))
        assertEquals("45 s", formatPictureAge(45_900))
        assertEquals("1 min 00 s", formatPictureAge(60_000))
        assertEquals("2 min 05 s", formatPictureAge(125_000))
        assertEquals("9 min 59 s", formatPictureAge(599_999))
        assertEquals("1 h 03 min", formatPictureAge(3_780_000))
    }

    @Test
    fun `an age is never rounded down across a unit`() {
        assertEquals("1 min 59 s", formatPictureAge(119_000))
        assertEquals("59 s", formatPictureAge(59_999))
    }

    @Test
    fun `a clock that ran backwards reads zero rather than negative`() {
        assertEquals("0 s", formatPictureAge(-5_000))
    }
}
