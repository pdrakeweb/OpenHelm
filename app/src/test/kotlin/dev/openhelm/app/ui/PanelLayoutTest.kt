package dev.openhelm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The side panel's arrangement is written out by hand rather than derived, because it is a spatial
 * layout and not a sorted list. That freedom is what these guard: a hand-written layout can drop a
 * control, and a dropped control is a command the user simply cannot send.
 */
class PanelLayoutTest {

    @Test
    fun `the panel places every control exactly once`() {
        assertEquals(
            MfdControl.entries.sortedBy { it.ordinal },
            panelLayoutControls.sortedBy { it.ordinal },
        )
    }

    @Test
    fun `the panel is arranged as asked for at the helm`() {
        // Home/Menu, then the dial, then a full-width Back, then zoom, then the rest. The dial
        // contributes nothing to this list; its position is checked by the ordering around it.
        assertEquals(
            listOf(
                MfdControl.HOME,
                MfdControl.MENU,
                MfdControl.BACK,
                MfdControl.ZOOM_IN,
                MfdControl.ZOOM_OUT,
                MfdControl.PANE,
                MfdControl.WAYPOINT,
            ),
            panelLayoutControls,
        )
    }

    @Test
    fun `reading order matches the panel, so the keypad cannot present a different sequence`() {
        assertEquals(MfdControl.panelOrder, panelLayoutControls)
    }
}
