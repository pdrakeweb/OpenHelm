package dev.openhelm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The persisted-palette upgrade mapping.
 *
 * The names on disk outlive the enum that wrote them: `DAY` and `DUSK` were written by installs
 * that predate the rename to HIGH_CONTRAST and DARK. Losing the mapping would silently reset
 * every upgraded install to the default — which for someone who chose high contrast means the
 * app quietly stops being readable in sun.
 */
class ParsePaletteTest {

    @Test
    fun `current names round-trip`() {
        HelmPalette.entries.forEach { palette ->
            assertEquals(palette, parsePalette(palette.name))
        }
    }

    @Test
    fun `legacy names written before the rename still map`() {
        assertEquals(HelmPalette.HIGH_CONTRAST, parsePalette("DAY"))
        assertEquals(HelmPalette.DARK, parsePalette("DUSK"))
    }

    @Test
    fun `never chosen means null, so the UI applies its own default`() {
        assertNull(parsePalette(null))
    }

    @Test
    fun `an unknown name falls back to null rather than crashing or guessing`() {
        assertNull(parsePalette("NEON"))
        assertNull(parsePalette(""))
    }
}
