package dev.openhelm.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Computed WCAG contrast ratios for every foreground/background pairing the app actually draws.
 *
 * Colour is the one part of a UI that cannot be checked by reading the code, and screenshots are
 * taken on a monitor in a room, which is the condition none of these palettes are for. The ratios
 * are arithmetic, so they can be checked here.
 *
 * Thresholds come from WCAG 2.2: **7:1** for body text and **4.5:1** for large text and icons at
 * AAA; 4.5:1 and 3:1 at AA. [HelmPalette.HIGH_CONTRAST] is held to AAA, since being readable in
 * direct sun is the entire reason it exists. [HelmPalette.DARK] is held to AA.
 *
 * [HelmPalette.NIGHT] is held to a lower floor on purpose, and it is worth being explicit about
 * why rather than quietly excluding it. WCAG's thresholds assume a screen read indoors, where the
 * only cost of raising contrast is raising contrast. On a dark bridge the cost is the navigator's
 * dark adaptation, which takes about twenty minutes to build and seconds to lose, and which matters
 * more than the app does. Night is a considered trade, not an oversight.
 */
class PaletteContrastTest {

    // ---- WCAG 2.2 relative luminance and contrast ratio ------------------------------------

    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        assertTrue(
            "%s is %.2f:1, below the %.1f:1 required".format(name, r, min),
            r >= min,
        )
    }

    /** Every pairing the app draws, checked against one threshold pair. */
    private fun checkPalette(palette: HelmPalette, body: Double, large: Double) {
        val s: ColorScheme = schemeFor(palette)
        val c = controlsFor(palette)
        val p = palette.name

        // Text on the page and on panels.
        assertContrast("$p onBackground/background", s.onBackground, s.background, body)
        assertContrast("$p onSurface/surface", s.onSurface, s.surface, body)
        assertContrast("$p onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant, body)
        // Secondary text is drawn on the page too, not only on cards.
        assertContrast("$p onSurfaceVariant/surface", s.onSurfaceVariant, s.surface, body)

        // Buttons. Every one of these is a container the user is meant to press.
        assertContrast("$p onPrimary/primary", s.onPrimary, s.primary, body)
        assertContrast("$p onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer, body)
        assertContrast("$p onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, body)
        assertContrast("$p onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, body)
        assertContrast("$p onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer, body)

        // The remote's own controls, which is what someone is looking at underway.
        assertContrast("$p key content/fill", c.keyContent, c.keyFill, body)
        assertContrast("$p key pressed content/fill", c.keyPressedContent, c.keyPressedFill, body)

        // A key must separate from the panel behind it, and the pressed state from the resting one.
        assertContrast("$p key fill/background", c.keyFill, s.background, large)
        assertContrast("$p key pressed fill/resting fill", c.keyPressedFill, c.keyFill, large)

        // Outlines and dividers. WCAG 1.4.11 asks 3:1 for the boundary of a UI component, which is
        // the same bar as large text, so this tracks the palette's own large threshold rather than
        // a flat number — night could not meet 3:1 without emitting light it exists to avoid.
        assertContrast("$p outline/background", s.outline, s.background, large)

        // The ring drawn around a *selected* choice, against the card it sits on.
        //
        // This pairing is the one that has actually failed. The outline was `onPrimaryContainer`,
        // chosen so it would contrast with the chip it surrounds — which it did. But a border
        // straddles a boundary, and its outer half lies over the **card**: in the bright palette
        // that made a pure-white ring on a near-white card, i.e. no ring at all, in the palette
        // whose entire purpose is being readable in glare. Measuring against the chip would have
        // passed and told us nothing; the surface behind is the one that matters.
        assertContrast("$p selection outline/card", s.primary, s.surfaceVariant, large)
    }

    @Test
    fun `high contrast meets WCAG AAA everywhere`() {
        checkPalette(HelmPalette.HIGH_CONTRAST, body = 7.0, large = 4.5)
    }

    @Test
    fun `dark meets WCAG AA everywhere`() {
        checkPalette(HelmPalette.DARK, body = 4.5, large = 3.0)
    }

    @Test
    fun `night holds a reduced floor, traded knowingly against dark adaptation`() {
        checkPalette(HelmPalette.NIGHT, body = 3.0, large = 1.5)
    }

    @Test
    fun `high contrast is the highest-contrast palette, by construction`() {
        val ratios = HelmPalette.entries.associateWith {
            ratio(schemeFor(it).onSurface, schemeFor(it).surface)
        }
        val hc = ratios.getValue(HelmPalette.HIGH_CONTRAST)
        HelmPalette.entries.filter { it != HelmPalette.HIGH_CONTRAST }.forEach {
            assertTrue(
                "%s reads %.2f:1, at or above high contrast's %.2f:1".format(it.name, ratios.getValue(it), hc),
                hc > ratios.getValue(it),
            )
        }
    }

    @Test
    fun `night is the dimmest palette, so cycling never brightens unexpectedly`() {
        val brightness = HelmPalette.entries.associateWith { luminance(schemeFor(it).background) }
        assertTrue(
            "night's page is not the darkest",
            brightness.getValue(HelmPalette.NIGHT) < brightness.getValue(HelmPalette.DARK),
        )
        assertTrue(
            "dark's page is not below high contrast's",
            brightness.getValue(HelmPalette.DARK) < brightness.getValue(HelmPalette.HIGH_CONTRAST),
        )
    }
}
