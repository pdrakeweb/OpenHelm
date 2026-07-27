package dev.openhelm.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Every icon in OpenHelm, drawn from scratch as Compose vector code.
 *
 * This is a clean-room requirement, not a stylistic preference — no vendor artwork is traced,
 * recoloured or used as a reference (see CLEAN-ROOM.md). Where a glyph echoes the display's own
 * keypad (Home, Menu, Back, Waypoint) that is because the *hardware* has those functions and the
 * muscle memory is worth preserving.
 *
 * Drawing rules, applied consistently so the set reads as one family:
 *
 * - **24×24 grid.** Most glyphs keep to a 20×20 live area with 2dp of padding, per Material's icon
 *   metrics. Three deliberately do not: [Wheel] and [PaletteDay] run their spokes and rays out to
 *   1.6–2dp from the edge, and [VideoOff]'s slash crosses the full frame, because in each case the
 *   shape *is* the extent — a wheel with short spokes reads as a gear, and a slash that stops
 *   short reads as a stray mark. Where a glyph breaks the padding it does so symmetrically, so it
 *   still sits on the same optical centre as the rest of the set.
 * - **2dp stroke weight**, round caps and joins on open strokes so they stay legible when scaled
 *   down; solid fills for the shapes that need to carry at a glance.
 * - **Geometric and bold** rather than delicate — these are read in sunlight, at speed, by someone
 *   whose attention belongs on the water.
 *
 * Sized at 24dp by default; call sites scale them up for the large helm keys.
 */
object MfdIcons {

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private val black = SolidColor(Color.Black)

    /** A house. The display's home page. */
    val Home: ImageVector by lazy {
        icon("Home") {
            path(fill = black) {
                moveTo(12f, 3f)
                lineTo(21f, 11f)
                lineTo(19f, 11f)
                lineTo(19f, 20f)
                lineTo(14f, 20f)
                lineTo(14f, 14f)
                lineTo(10f, 14f)
                lineTo(10f, 20f)
                lineTo(5f, 20f)
                lineTo(5f, 11f)
                lineTo(3f, 11f)
                close()
            }
        }
    }

    /** Three rules. The display's menu. */
    val Menu: ImageVector by lazy {
        icon("Menu") {
            path(
                stroke = black,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(4f, 7f); lineTo(20f, 7f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(4f, 17f); lineTo(20f, 17f)
            }
        }
    }

    /** A left arrow. Steps back in the display's own UI — not app navigation. */
    val Back: ImageVector by lazy {
        icon("Back") {
            path(
                stroke = black,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(20f, 12f); lineTo(5f, 12f)
                moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
            }
        }
    }

    /** Magnifier with a plus — zoom in, which the display treats as a range change. */
    val ZoomIn: ImageVector by lazy {
        icon("ZoomIn") {
            magnifier()
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(7f, 10f); lineTo(13f, 10f)
                moveTo(10f, 7f); lineTo(10f, 13f)
            }
        }
    }

    /** Magnifier with a minus — zoom out. */
    val ZoomOut: ImageVector by lazy {
        icon("ZoomOut") {
            magnifier()
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(7f, 10f); lineTo(13f, 10f)
            }
        }
    }

    /** Two overlapping panes — switches which pane the display has active. */
    val SwapPane: ImageVector by lazy {
        icon("SwapPane") {
            path(
                stroke = black,
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 4f)
                lineTo(14f, 4f)
                lineTo(14f, 14f)
                lineTo(4f, 14f)
                close()
            }
            path(
                stroke = black,
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10f, 10f)
                lineTo(20f, 10f)
                lineTo(20f, 20f)
                lineTo(10f, 20f)
                close()
            }
        }
    }

    /** A flag on a staff — drop a waypoint (the display's WPT key). */
    val Waypoint: ImageVector by lazy {
        icon("Waypoint") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(6f, 3f); lineTo(6f, 21f)
            }
            path(fill = black) {
                moveTo(7.5f, 4f)
                lineTo(19f, 8f)
                lineTo(7.5f, 12f)
                close()
            }
        }
    }

    /** A tick — an action already taken, or one being accepted. Used for Settings' Done. */
    val Confirm: ImageVector by lazy {
        icon("Confirm") {
            path(
                stroke = black,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
            }
        }
    }

    /**
     * Gear. Settings.
     *
     * Stroked rather than filled-with-a-punched-hub: a tinted icon has no access to the surface
     * behind it, so a "hole" drawn in a fixed colour would be wrong on any background. An outlined
     * rim plus an outlined hub tints as one colour and reads the same at every size.
     */
    val Settings: ImageVector by lazy {
        icon("Settings") {
            path(
                stroke = black,
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10.6f, 2.6f)
                lineTo(13.4f, 2.6f)
                lineTo(13.9f, 5.1f)
                lineTo(15.8f, 5.9f)
                lineTo(17.9f, 4.5f)
                lineTo(19.5f, 6.1f)
                lineTo(18.1f, 8.2f)
                lineTo(18.9f, 10.1f)
                lineTo(21.4f, 10.6f)
                lineTo(21.4f, 13.4f)
                lineTo(18.9f, 13.9f)
                lineTo(18.1f, 15.8f)
                lineTo(19.5f, 17.9f)
                lineTo(17.9f, 19.5f)
                lineTo(15.8f, 18.1f)
                lineTo(13.9f, 18.9f)
                lineTo(13.4f, 21.4f)
                lineTo(10.6f, 21.4f)
                lineTo(10.1f, 18.9f)
                lineTo(8.2f, 18.1f)
                lineTo(6.1f, 19.5f)
                lineTo(4.5f, 17.9f)
                lineTo(5.9f, 15.8f)
                lineTo(5.1f, 13.9f)
                lineTo(2.6f, 13.4f)
                lineTo(2.6f, 10.6f)
                lineTo(5.1f, 10.1f)
                lineTo(5.9f, 8.2f)
                lineTo(4.5f, 6.1f)
                lineTo(6.1f, 4.5f)
                lineTo(8.2f, 5.9f)
                lineTo(10.1f, 5.1f)
                close()
            }
            path(stroke = black, strokeLineWidth = 2f) {
                circle(12f, 12f, 3.2f)
            }
        }
    }

    /** Keyboard/marker for typing an address by hand. */
    val ManualEntry: ImageVector by lazy {
        icon("ManualEntry") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 6f)
                lineTo(21f, 6f)
                lineTo(21f, 18f)
                lineTo(3f, 18f)
                close()
            }
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(7f, 10f); lineTo(7f, 10f)
                moveTo(11f, 10f); lineTo(11f, 10f)
                moveTo(15f, 10f); lineTo(15f, 10f)
                moveTo(7f, 14f); lineTo(17f, 14f)
            }
        }
    }

    /** A screen with a slash — video off. */
    val VideoOff: ImageVector by lazy {
        icon("VideoOff") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 5f)
                lineTo(21f, 5f)
                lineTo(21f, 17f)
                lineTo(3f, 17f)
                close()
            }
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 20f); lineTo(20f, 2f)
            }
        }
    }

    /** A screen — video on. */
    val VideoOn: ImageVector by lazy {
        icon("VideoOn") {
            path(stroke = black, strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 5f)
                lineTo(21f, 5f)
                lineTo(21f, 17f)
                lineTo(3f, 17f)
                close()
            }
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 21f); lineTo(16f, 21f)
            }
        }
    }

    /**
     * Power symbol — end the session.
     *
     * The ring is the *major* arc from upper-left round through the bottom to upper-right, leaving
     * the gap at the top for the stem: hence `isMoreThanHalf = true`, and `isPositiveArc = false`
     * because that path runs anticlockwise in screen coordinates (y grows downward).
     */
    val Disconnect: ImageVector by lazy {
        icon("Disconnect") {
            path(
                stroke = black,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(12f, 2.6f); lineTo(12f, 11f)
            }
            path(
                stroke = black,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(7.5f, 7.5f)
                arcTo(6.36f, 6.36f, 0f, true, false, 16.5f, 7.5f)
            }
        }
    }

    /** A ship's wheel — OpenHelm's mark, matching the launcher icon. */
    val Wheel: ImageVector by lazy {
        icon("Wheel") {
            path(stroke = black, strokeLineWidth = 2f) {
                circle(12f, 12f, 8f)
            }
            path(fill = black) {
                circle(12f, 12f, 2.6f)
            }
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 1.6f); lineTo(12f, 6f)
                moveTo(12f, 18f); lineTo(12f, 22.4f)
                moveTo(1.6f, 12f); lineTo(6f, 12f)
                moveTo(18f, 12f); lineTo(22.4f, 12f)
            }
        }
    }

    /**
     * The day palette: a sun with full rays. The brightest of the three glyphs, so the cycle's
     * position reads from the icon's weight alone before any label is looked at.
     */
    val PaletteDay: ImageVector by lazy {
        icon("PaletteDay") {
            path(fill = black) {
                circle(12f, 12f, 4.6f)
            }
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 2f); lineTo(12f, 4.6f)
                moveTo(12f, 19.4f); lineTo(12f, 22f)
                moveTo(2f, 12f); lineTo(4.6f, 12f)
                moveTo(19.4f, 12f); lineTo(22f, 12f)
                moveTo(4.9f, 4.9f); lineTo(6.8f, 6.8f)
                moveTo(17.2f, 17.2f); lineTo(19.1f, 19.1f)
                moveTo(19.1f, 4.9f); lineTo(17.2f, 6.8f)
                moveTo(6.8f, 17.2f); lineTo(4.9f, 19.1f)
            }
        }
    }

    /** The dusk palette: a half sun on the horizon — the sun setting, with the rays it has left. */
    val PaletteDusk: ImageVector by lazy {
        icon("PaletteDusk") {
            // The horizon the sun is sitting on.
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(2.5f, 17f); lineTo(21.5f, 17f)
            }
            // Half a disc, flat side down on that line.
            path(fill = black) {
                moveTo(7.4f, 17f)
                arcToRelative(4.6f, 4.6f, 0f, true, true, 9.2f, 0f)
                close()
            }
            path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 3f); lineTo(12f, 5.4f)
                moveTo(3.6f, 12.4f); lineTo(5.6f, 12.4f)
                moveTo(18.4f, 12.4f); lineTo(20.4f, 12.4f)
                moveTo(5.9f, 6.6f); lineTo(7.5f, 8.2f)
                moveTo(18.1f, 6.6f); lineTo(16.5f, 8.2f)
            }
        }
    }

    /**
     * The night palette: a crescent moon, drawn as one disc with a second subtracted via the
     * even-odd fill rule rather than as an arc pair, so the inner edge stays a true circle at any
     * size.
     */
    val PaletteNight: ImageVector by lazy {
        icon("PaletteNight") {
            path(fill = black, pathFillType = PathFillType.EvenOdd) {
                circle(12f, 12f, 9f)
                circle(15.8f, 8.8f, 8.4f)
            }
        }
    }

    // ---- shared shape helpers -------------------------------------------------------------

    /** The magnifier body shared by [ZoomIn] and [ZoomOut], so the two are exactly consistent. */
    private fun ImageVector.Builder.magnifier() {
        path(stroke = black, strokeLineWidth = 2f) {
            circle(10f, 10f, 6f)
        }
        path(stroke = black, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(14.5f, 14.5f); lineTo(20f, 20f)
        }
    }
}

/** A circle, as two half-arcs — the portable way to express one in a vector path. */
private fun androidx.compose.ui.graphics.vector.PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    close()
}
