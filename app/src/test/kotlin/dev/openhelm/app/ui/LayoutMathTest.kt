package dev.openhelm.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.openhelm.protocol.MfdKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the adaptive layout.
 *
 * Every case here is a window shape that either caused a real defect or sits at a boundary where
 * one would appear. The emulator can be resized to some of them and not to others, and none of
 * them are quick to check by eye — which is exactly the argument for pinning them here.
 */
class LayoutMathTest {

    // ---- letterbox axis choice ------------------------------------------------------------

    @Test
    fun `a wide short pane fills its height so the picture cannot spill upward`() {
        // The shape that broke it: a phone in landscape, video pane beside the control panel.
        // Filling the width here computes a height larger than the pane and draws over the
        // status bar.
        assertTrue(heightIsTheLimit(widthDp = 640f, heightDp = 340f))
    }

    @Test
    fun `a tall narrow pane fills its width`() {
        assertFalse(heightIsTheLimit(widthDp = 400f, heightDp = 600f))
    }

    @Test
    fun `an exactly 5 to 3 pane fills its width, and either choice is correct`() {
        assertFalse(heightIsTheLimit(widthDp = 500f, heightDp = 300f))
    }

    @Test
    fun `the zero-height first measurement pass does not divide by zero`() {
        assertFalse(heightIsTheLimit(widthDp = 800f, heightDp = 0f))
    }

    // ---- keypad sizing --------------------------------------------------------------------

    @Test
    fun `the keypad shrinks to fit a compact-height landscape window`() {
        // 891 x 411dp less the status bar: the geometry tests-14 uses. The old hard-coded 72dp
        // needed 312dp for the named cluster alone and clipped its bottom row.
        val size = keySizeFor(
            availableWidth = 891.dp,
            availableHeight = 340.dp,
            gap = 8.dp,
            clusterGap = 28.dp,
            maxKeySize = 88.dp,
        )
        assertTrue("expected to shrink below 88dp, got $size", size < 88.dp)
        // Four rows and three gaps must fit in what we were given.
        assertTrue("cluster overflows: $size", size * 4 + 8.dp * 3 <= 340.dp)
    }

    @Test
    fun `the keypad never goes below the helm touch minimum, however small the window`() {
        val size = keySizeFor(
            availableWidth = 200.dp,
            availableHeight = 120.dp,
            gap = 8.dp,
            clusterGap = 28.dp,
            maxKeySize = 88.dp,
        )
        // At this size it cannot fit, and that is what the scroll is for — but the keys stay legal.
        assertEquals(MinHelmTarget, size)
    }

    @Test
    fun `a tablet window is capped rather than growing without bound`() {
        val size = keySizeFor(
            availableWidth = 1600.dp,
            availableHeight = 1000.dp,
            gap = 8.dp,
            clusterGap = 28.dp,
            maxKeySize = 88.dp,
        )
        assertEquals(88.dp, size)
    }

    @Test
    fun `width can be the binding constraint, not only height`() {
        val size = keySizeFor(
            availableWidth = 420.dp,
            availableHeight = 900.dp,
            gap = 8.dp,
            clusterGap = 28.dp,
            maxKeySize = 88.dp,
        )
        // (420 - 24 - 28) / 5 = 73.6dp — set by the width, well under the height's allowance.
        assertTrue("expected the width to bind, got $size", size < 80.dp)
    }

    // ---- icon scaling ---------------------------------------------------------------------

    @Test
    fun `icons scale with their key but stay within legible bounds`() {
        assertEquals(18.dp, iconSizeFor(40.dp))   // clamped up
        assertEquals(30.dp, iconSizeFor(120.dp))  // clamped down
        assertTrue(iconSizeFor(88.dp) in 18.dp..30.dp)
    }

    // ---- dial direction sectors -----------------------------------------------------------

    @Test
    fun `each dial sector maps to the key drawn in it`() {
        // Screen coordinates: y grows downward, so negative y is up.
        assertEquals(MfdKey.UP, directionAt(Offset(0f, -50f)).second)
        assertEquals(MfdKey.DOWN, directionAt(Offset(0f, 50f)).second)
        assertEquals(MfdKey.LEFT, directionAt(Offset(-50f, 0f)).second)
        assertEquals(MfdKey.RIGHT, directionAt(Offset(50f, 0f)).second)
    }

    @Test
    fun `a touch just inside a sector boundary picks that sector, not its neighbour`() {
        // Just above the horizontal on the right-hand side: still RIGHT, not UP.
        assertEquals(MfdKey.RIGHT, directionAt(Offset(50f, -20f)).second)
        // Just right of vertical, above centre: UP, not RIGHT.
        assertEquals(MfdKey.UP, directionAt(Offset(20f, -50f)).second)
    }
}
