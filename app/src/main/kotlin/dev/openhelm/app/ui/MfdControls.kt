package dev.openhelm.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
import dev.openhelm.app.ui.icons.MfdIcons
import dev.openhelm.protocol.MfdKey

/**
 * The single canonical description of every control the display exposes: one label, one icon, one
 * ordering, used by **both** the side panel and the full-screen keypad.
 *
 * This exists because the two modes previously disagreed with each other — the same function
 * appeared as `Rng −` in one and a bare `−` in the other, `Swch` in one and `Pane` in the other,
 * and Home/Menu swapped places between them. Someone learning the controls in one mode had to
 * re-learn them in the other, which is exactly the wrong thing to ask of a person at a helm.
 *
 * Labels are spelled out rather than abbreviated. The vendor's own panel uses cryptic
 * abbreviations because it is silkscreening a physical key; a phone has room for the word, and
 * "Zoom in" needs no interpreting where `Rng +` does.
 */
enum class MfdControl(
    val key: MfdKey,
    val label: String,
    val icon: ImageVector,
    /** Spoken/described for accessibility and long-press hints; says what the display will do. */
    val description: String,
) {
    HOME(MfdKey.HOME, "Home", MfdIcons.Home, "Go to the display's home page"),
    MENU(MfdKey.MENU, "Menu", MfdIcons.Menu, "Open the display's menu"),
    ZOOM_IN(MfdKey.RANGE_IN, "Zoom in", MfdIcons.ZoomIn, "Zoom the chart in (decrease range)"),
    ZOOM_OUT(MfdKey.RANGE_OUT, "Zoom out", MfdIcons.ZoomOut, "Zoom the chart out (increase range)"),
    BACK(MfdKey.BACK, "Back", MfdIcons.Back, "Go back in the display's menu"),
    PANE(MfdKey.SWITCH, "Pane", MfdIcons.SwapPane, "Switch the display's active pane"),
    WAYPOINT(MfdKey.WPT, "Waypoint", MfdIcons.Waypoint, "Place a waypoint"),
    ;

    companion object {
        /**
         * The named keys in the order both modes present them, most-reached-for first.
         *
         * This is [entries], not a second hand-written list. It used to be one, and it had already
         * drifted: the enum read Home, Menu, Back, Zoom in… while the list read Home, Menu, Zoom
         * in, Zoom out, Back… — so the type whose entire stated purpose is that the two modes
         * cannot disagree was itself carrying two orderings. Declaration order is the ordering, and
         * there is nowhere left for a second one to live.
         */
        val panelOrder: List<MfdControl> get() = entries
    }
}
