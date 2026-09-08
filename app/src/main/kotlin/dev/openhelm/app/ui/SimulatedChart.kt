package dev.openhelm.app.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A synthetic chartplotter scene, drawn from scratch, for simulation mode.
 *
 * **Nothing here is copied from a display.** The reference photographs of a real MFD were used the
 * same way the protocol documentation was: to establish *facts about the layout* — that there is a
 * data bar across the top carrying a few numeric fields and a way into the menu, with the chart
 * filling everything below it — after which every pixel is generated. The coastline is a sum of
 * sines, the soundings are arithmetic, the place names are invented, and there is no vendor
 * branding, iconography or cartography anywhere in it. See CLEAN-ROOM.md; a screenshot of a
 * display, and the licensed chart data inside it, could not be shipped in this repository.
 *
 * It replaces a set of colour bars. Bars proved the pipeline drew *something*, but they made
 * simulation useless for the thing it is actually for — judging whether the panel, the palettes and
 * the letterboxing work against a picture with the density and tonal range of a real chart. A chart
 * is mostly pale, has fine dark detail everywhere, and carries small text that has to stay readable
 * under the night scrim. Colour bars have none of those properties.
 *
 * Drawn in a virtual 800×480 space — the display's own resolution — and scaled to whatever the pane
 * gives it, so the proportions match what a real session shows.
 *
 * Text scales down, but never up: every font size here is `size * fontScale`, where `fontScale` is
 * `u` (the same width ratio every line and shape scales by) *clamped to 1*. The pane this scene
 * normally fills is usually wider than the 800px virtual reference, so `u` itself is usually above
 * 1 — multiplying font sizes by the unclamped `u`, as an earlier version of this file did, inflated
 * them past their tuned size in the ordinary case, which is what actually overflowed the data bar.
 * Only a picture-in-picture window, smaller than the reference, should ever shrink the text; nothing
 * should ever grow it.
 *
 * The data bar's own height follows the font, not the chart. Sizing it from `y(DATA_BAR_HEIGHT)` —
 * the same uncapped scale every chart line and position uses — was the next mistake this file made:
 * it fixed the small-window overflow but then grew the bar without a ceiling as the pane got bigger,
 * while the text inside it stayed capped at its tuned size right alongside it. At an ordinary
 * full-screen size (`u` well above 1) that produced a bar several times taller than the two lines of
 * text sitting in it — correct proportion at exactly `u = 1`, wrong everywhere above it, which is
 * most of the time this pane actually renders. [barHeight] uses the same clamped `fontScale` as the
 * text itself, so the two stay in the same proportion at every size: capped together above `u = 1`,
 * shrinking together below it.
 */
internal fun DrawScope.drawSimulatedChart(measurer: TextMeasurer, frame: Int) {
    val u = size.width / VIRTUAL_W
    fun x(v: Float) = v * u
    fun y(v: Float) = v * (size.height / VIRTUAL_H)

    val t = frame / 15f // seconds
    val barHeight = DATA_BAR_HEIGHT * u.coerceAtMost(1f)

    drawChartBase(u, ::x, ::y, barHeight, measurer, t)
    drawDataBar(::x, u, barHeight, measurer, t)
    drawCursor(::x, ::y, u)
}

// ---- chart ---------------------------------------------------------------------------------

/**
 * The coastline: a sum of three sines, so it wanders like a shore without being anywhere real.
 *
 * Top-level rather than local to the drawing, because the soundings and navigation marks have to be
 * checked against it. Hand-placed points drifted onto the land when the chart widened, and a
 * sounding printed on a beach is the sort of detail that makes a chart read as fake immediately.
 */
private fun shore(px: Float): Float =
    300f + sin(px / 118f) * 34f + sin(px / 47f + 1.7f) * 13f + sin(px / 23f + 0.4f) * 5f

/** True where a point is far enough seaward of [shore] to carry a sounding or a mark. */
private fun inWater(px: Float, py: Float): Boolean = py < shore(px) - 8f

/** IHO chart conventions: pale open water, cyan shoal, buff land. Universal, not anyone's house style. */
private val DeepWater = Color(0xFFF2F4F3)
private val ShoalWater = Color(0xFF9FD8E6)
private val Land = Color(0xFFDCC48C)
private val ContourInk = Color(0xFF5C7C93)
private val ChartInk = Color(0xFF37474F)
private val RouteInk = Color(0xFFC2185B)
private val GraticuleInk = Color(0x33546E7A)

private fun DrawScope.drawChartBase(
    u: Float,
    x: (Float) -> Float,
    y: (Float) -> Float,
    barHeight: Float,
    measurer: TextMeasurer,
    t: Float,
) {
    // Unlike stroke widths and positions, text is never scaled *up* past its original size — those
    // fixed sp values were already tuned to look right at the sizes this pane normally renders at,
    // which are usually wider than the 800px virtual reference (u > 1). Multiplying by u there, as
    // an earlier version of this fix did, inflated the data bar's text past the bar itself. Only
    // shrinking for u < 1 — a picture-in-picture window, smaller than the reference — is correct.
    val fontScale = u.coerceAtMost(1f)

    drawRect(DeepWater)

    fun band(offset: Float): Path = Path().apply {
        moveTo(x(0f), y(shore(0f) + offset))
        var px = 0f
        while (px <= VIRTUAL_W) {
            lineTo(x(px), y(shore(px) + offset))
            px += 8f
        }
        lineTo(x(VIRTUAL_W), y(VIRTUAL_H))
        lineTo(x(0f), y(VIRTUAL_H))
        close()
    }

    // Shoal water first, then land over it, so the cyan reads as a band along the shore.
    drawPath(band(-46f), ShoalWater)
    drawPath(band(0f), Land)

    // Depth contours seaward of the shoal.
    listOf(-70f, -104f, -146f).forEachIndexed { i, off ->
        val p = Path()
        var px = 0f
        var first = true
        while (px <= VIRTUAL_W) {
            val py = y(shore(px) + off)
            if (first) { p.moveTo(x(px), py); first = false } else p.lineTo(x(px), py)
            px += 8f
        }
        drawPath(
            p,
            ContourInk.copy(alpha = 0.7f - i * 0.15f),
            style = Stroke(width = 1f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f * u, 5f * u))),
        )
    }

    // Graticule, starting just below the data bar. barHeight is already a real pixel offset (see
    // drawSimulatedChart's doc on why it isn't run through y() like everything else here), so the
    // horizontal lines are spaced out in real pixels too rather than mixing coordinate spaces.
    val gStroke = Stroke(width = 1f * u)
    for (gx in 1..4) {
        val px = VIRTUAL_W * gx / 5f
        drawLine(GraticuleInk, Offset(x(px), barHeight), Offset(x(px), y(VIRTUAL_H)), gStroke.width)
    }
    for (gy in 1..3) {
        val py = barHeight + (y(VIRTUAL_H) - barHeight) * gy / 4f
        drawLine(GraticuleInk, Offset(0f, py), Offset(x(VIRTUAL_W), py), gStroke.width)
    }

    // Spot soundings: fixed positions, so the chart does not shimmer between frames.
    val soundingStyle = TextStyle(color = ChartInk, fontSize = (8f * fontScale).sp, fontFamily = FontFamily.SansSerif)
    SOUNDINGS.filter { (sx, sy, _) -> inWater(sx, sy) }.forEach { (sx, sy, depth) ->
        val line = measurer.measure("$depth", soundingStyle)
        drawText(line, topLeft = Offset(x(sx), y(sy)))
    }

    // Navigation marks along the channel.
    MARKS.filter { (mx, my) -> inWater(mx, my) }.forEach { (mx, my) ->
        drawCircle(Color(0xFF2E7D32), radius = 3f * u, center = Offset(x(mx), y(my)))
        drawLine(
            ChartInk,
            Offset(x(mx), y(my)),
            Offset(x(mx), y(my - 9f)),
            strokeWidth = 1.2f * u,
        )
    }

    // A planned route, and the vessel running down it.
    val route = Path().apply {
        moveTo(x(ROUTE[0].first), y(ROUTE[0].second))
        ROUTE.drop(1).forEach { (rx, ry) -> lineTo(x(rx), y(ry)) }
    }
    drawPath(
        route,
        RouteInk,
        style = Stroke(width = 1.6f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(11f * u, 7f * u))),
    )
    ROUTE.forEach { (rx, ry) ->
        drawCircle(RouteInk, radius = 3.5f * u, center = Offset(x(rx), y(ry)), style = Stroke(width = 1.6f * u))
    }

    // The vessel: a slow loop along the route, heading tangent to travel.
    val legs = ROUTE.size - 1
    val prog = ((t / 9f) % legs)
    val leg = prog.toInt().coerceIn(0, legs - 1)
    val f = prog - leg
    val (ax, ay) = ROUTE[leg]
    val (bx, by) = ROUTE[leg + 1]
    val vx = ax + (bx - ax) * f
    val vy = ay + (by - ay) * f
    val heading = Math.toDegrees(kotlin.math.atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat() + 90f

    rotate(heading, pivot = Offset(x(vx), y(vy))) {
        val boat = Path().apply {
            moveTo(x(vx), y(vy - 11f))
            lineTo(x(vx + 7f), y(vy + 8f))
            lineTo(x(vx), y(vy + 4f))
            lineTo(x(vx - 7f), y(vy + 8f))
            close()
        }
        drawPath(boat, Color(0xFF0D47A1))
        drawPath(boat, Color.White, style = Stroke(width = 1.2f * u))
    }

    // Place names, in this project's own invented geography.
    val nameStyle = TextStyle(
        color = ChartInk,
        fontSize = (9f * fontScale).sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.SansSerif,
    )
    PLACES.forEach { (px, py, name) ->
        drawText(measurer.measure(name, nameStyle), topLeft = Offset(x(px), y(py)))
    }

    // Scale bar and orientation. Kept clear of the data bar, which is painted over the chart
     // afterwards and swallowed both labels when they sat any higher. The clearance itself scales
     // with the chart normally (u, uncapped) — it is breathing room, not text — added to barHeight,
     // which is already a real pixel offset.
    val barY = barHeight + 22f * u
    val barLeft = x(24f)
    val barRight = x(120f)
    drawLine(ChartInk, Offset(barLeft, barY), Offset(barRight, barY), strokeWidth = 1.4f * u)
    drawLine(ChartInk, Offset(barLeft, barY - 4f * u), Offset(barLeft, barY + 4f * u), strokeWidth = 1.4f * u)
    drawLine(ChartInk, Offset(barRight, barY - 4f * u), Offset(barRight, barY + 4f * u), strokeWidth = 1.4f * u)
    val scaleStyle = TextStyle(color = ChartInk, fontSize = (9f * fontScale).sp, fontWeight = FontWeight.Medium)
    val scaleLine = measurer.measure("2 nm", scaleStyle)
    drawText(scaleLine, topLeft = Offset(barRight + x(10f), barY - scaleLine.size.height / 2f))
    val northLine = measurer.measure("North-Up", scaleStyle)
    drawText(northLine, topLeft = Offset(x(400f) - northLine.size.width / 2f, barY - northLine.size.height / 2f))
}

// ---- data bar ------------------------------------------------------------------------------

private fun DrawScope.drawDataBar(
    x: (Float) -> Float,
    u: Float,
    barHeight: Float,
    measurer: TextMeasurer,
    t: Float,
) {
    drawRect(Color(0xFF1B2A38), size = Size(size.width, barHeight))
    drawLine(Color(0xFF3E5265), Offset(0f, barHeight), Offset(size.width, barHeight), strokeWidth = 1f * u)

    // Never scaled up past its tuned size — see drawSimulatedChart's fontScale/barHeight doc. The
    // bar itself is capped at the same factor (barHeight), so oversized text here does not just
    // look wrong, it draws outside the bar entirely — and an oversized *bar* leaves the text
    // looking lost inside far more space than it needs.
    val fontScale = u.coerceAtMost(1f)
    val labelStyle = TextStyle(color = Color(0xFF90A4AE), fontSize = (8f * fontScale).sp, fontWeight = FontWeight.Medium)
    val valueStyle = TextStyle(
        color = Color.White,
        fontSize = (17f * fontScale).sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.SansSerif,
    )

    // Values drift slowly, so the bar reads as live instrumentation rather than a static caption.
    val sog = 6.4f + sin(t / 5.5f) * 0.6f
    val cog = 74f + sin(t / 7.3f) * 5f
    val dpt = 18.2f + sin(t / 4.1f) * 2.4f

    // Locale.ROOT throughout: a comma-decimal locale would render "6,4 kn", which reads as two
    // fields on an instrument bar.
    val fields = listOf(
        "SOG" to String.format(Locale.ROOT, "%.1f kn", sog),
        "COG" to String.format(Locale.ROOT, "%03d°", cog.roundToInt().mod(360)),
        "DPT" to String.format(Locale.ROOT, "%.1f m", dpt),
        // A clock rather than a stopwatch: minutes from a fixed afternoon start, so the field
        // reads the way the real one does instead of counting up from zero.
        "TIME" to String.format(
            Locale.ROOT,
            "%02d:%02d",
            ((CLOCK_START_MIN + t / 60) / 60).toInt().mod(24),
            (CLOCK_START_MIN + t / 60).toInt().mod(60),
        ),
    )

    // The label/value offsets are real pixels too (fontScale, not y()) — they are positions
    // *within* text-driven content, so they follow the text's own scale, not the chart's.
    var cx = x(14f)
    fields.forEach { (label, value) ->
        drawText(measurer.measure(label, labelStyle), topLeft = Offset(cx, DATA_BAR_LABEL_Y * fontScale))
        val v = measurer.measure(value, valueStyle)
        drawText(v, topLeft = Offset(cx, DATA_BAR_VALUE_Y * fontScale))
        cx += maxOf(v.size.width.toFloat(), x(72f)) + x(24f)
    }

    drawMenuButton(x, u, barHeight, measurer)
}

/**
 * The menu, closed.
 *
 * The scene used to draw the menu open down the right-hand third of the screen. That is a state the
 * display spends very little time in, and it cost a third of the chart permanently — which is the
 * part of the picture the panel and the palettes actually have to be judged against. A closed button
 * says the menu exists without standing in front of the thing it opens over.
 */
private fun DrawScope.drawMenuButton(
    x: (Float) -> Float,
    u: Float,
    barHeight: Float,
    measurer: TextMeasurer,
) {
    val left = x(694f)
    // Centred in the data bar rather than a fixed offset from its top: the button's own size
    // scales with the chart normally (u, uncapped, like the rest of this icon), independent of the
    // bar's own height — it is the bar that moved around it, not the other way round.
    val top = barHeight / 2f - 15f * u
    val bottom = barHeight / 2f + 15f * u

    // Three bars, then the word — measured before the box is drawn, not assumed to fit a fixed
    // 786f right edge. That fixed edge left only ~14 virtual px of margin to the chart's own
    // right boundary, invisible at every full-screen size (the pane is always far wider than the
    // 800px virtual reference there) and shrinking harmlessly together with the label in a small
    // picture-in-picture window (u < 1, so both shrink at the same rate) — but a PiP window whose
    // width happens to land near the 800px reference itself (u ≈ 1, which large-phone/high-density
    // PiP windows do) hits the one case never exercised by either: label and box at their tuned,
    // unshrunk size, where the label was already wider than the box budgeted for it. The overflow
    // then had nowhere to go but past the chart's own right edge — clipped by the window itself.
    val barX = left + x(11f)
    val barW = x(15f)
    val labelStyle = TextStyle(color = Color(0xFFE3EAF0), fontSize = (12f * u.coerceAtMost(1f)).sp, fontWeight = FontWeight.Medium)
    val label = measurer.measure("Menu", labelStyle)
    // Never past the canvas itself: text drawn past this point is clipped by the window, not by
    // anything of ours, so it is the one bound that cannot be allowed to lose. The label's natural
    // position (right after the icon) wins whenever there is room; only when the canvas is too
    // narrow for both does it give way and slide left, closer to the icon than it would like.
    val safeRight = size.width - x(8f)
    val naturalLabelLeft = barX + barW + x(9f)
    val labelLeft = minOf(naturalLabelLeft, safeRight - label.size.width)
    val contentRight = labelLeft + label.size.width + x(11f)
    val right = maxOf(x(786f), contentRight).coerceAtMost(safeRight)

    drawRoundRect(
        color = Color(0xFF2C3E50),
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(4f * u, 4f * u),
    )
    drawRoundRect(
        color = Color(0xFF5B7A94),
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(4f * u, 4f * u),
        style = Stroke(width = 1f * u),
    )

    repeat(3) { i ->
        val by = top + (bottom - top) / 2f + (i - 1) * 6f * u
        drawLine(
            Color(0xFFE3EAF0),
            Offset(barX, by),
            Offset(barX + barW, by),
            strokeWidth = 2f * u,
        )
    }
    drawText(
        label,
        topLeft = Offset(labelLeft, (top + bottom) / 2f - label.size.height / 2f),
    )
}

// ---- cursor --------------------------------------------------------------------------------

/** The chart cursor the arrow keys and the dial move. Outlined so it survives any chart tone under it. */
private fun DrawScope.drawCursor(x: (Float) -> Float, y: (Float) -> Float, u: Float) {
    val c = Offset(x(400f), y(268f))
    val arm = 15f * u
    listOf(Color.Black to 3.6f * u, Color.White to 1.8f * u).forEach { (color, w) ->
        drawLine(color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = w)
        drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = w)
    }
    drawCircle(Color.Black, radius = 4.2f * u, center = c, style = Stroke(width = 2.4f * u))
    drawCircle(Color.White, radius = 4.2f * u, center = c, style = Stroke(width = 1.2f * u))
}

// ---- fixed scene data ----------------------------------------------------------------------

/** 14:20, so the clock starts somewhere plausible rather than at midnight. */
private const val CLOCK_START_MIN = 14f * 60f + 20f

// internal, not private: SimulatedRemoteScreen's SimActionField overlay needs to reproduce this
// exact scale factor and bar height in Compose's dp space, to stay aligned with what this file
// draws in raw pixels underneath it — see the doc on that overlay for why a second, independently
// hand-tuned dp size drifted from this one at every density that wasn't the one it was eyeballed at.
internal const val VIRTUAL_W = 800f
private const val VIRTUAL_H = 480f

/**
 * The data bar's height in real pixels *at `u = 1` and below* — see `drawSimulatedChart`'s doc for
 * why this is multiplied by the same clamped `fontScale` the bar's own text uses, rather than run
 * through the chart's ordinary (uncapped) `y()`, and why both bugs that this constant has already
 * been through (overflowing a too-short bar, then a bar that outgrew its own text) were really one
 * bug: the bar's size was never tied to the one thing that actually determines how tall it needs to
 * be, which is the text sitting in it.
 *
 * Sized for a two-line stack (8sp label, 17sp value) at typical phone density: a 17sp line's real
 * rendered height including line-height is on the order of 55-70 real pixels, not the 44 this
 * constant started at.
 */
internal const val DATA_BAR_HEIGHT = 130f

/** Label line (8sp) top, with a small margin above it — multiplied by `fontScale`, like the bar. */
private const val DATA_BAR_LABEL_Y = 8f

/**
 * Value line (17sp) top — multiplied by `fontScale`, like the bar. Below the label with enough of a
 * gap that the two lines cannot touch even at a high-density device's rendered line-height, and
 * enough room below it, within [DATA_BAR_HEIGHT], for that same line to fully render without
 * touching the bar's own bottom edge.
 */
private const val DATA_BAR_VALUE_Y = 46f

/** Depth in metres at a fixed spot. Constant so the chart does not shimmer frame to frame. */
private val SOUNDINGS = listOf(
    Triple(60f, 120f, 31), Triple(140f, 96f, 27), Triple(232f, 132f, 24), Triple(316f, 104f, 29),
    Triple(404f, 140f, 22), Triple(470f, 108f, 26), Triple(96f, 190f, 18), Triple(190f, 214f, 15),
    Triple(286f, 186f, 19), Triple(372f, 226f, 12), Triple(452f, 196f, 16), Triple(508f, 240f, 9),
    Triple(150f, 268f, 11), Triple(250f, 300f, 7), Triple(340f, 288f, 8), Triple(430f, 262f, 13),
    Triple(556f, 128f, 28), Triple(624f, 96f, 33), Triple(700f, 136f, 25), Triple(762f, 104f, 30),
    Triple(578f, 206f, 17), Triple(660f, 232f, 14), Triple(742f, 198f, 20), Triple(706f, 250f, 12),
    Triple(596f, 246f, 11), Triple(772f, 268f, 10),
)

private val MARKS = listOf(
    124f to 236f, 268f to 262f, 396f to 250f, 486f to 250f,
    600f to 262f, 690f to 244f, 758f to 262f,
)

private val ROUTE = listOf(
    72f to 168f, 196f to 208f, 322f to 190f, 448f to 236f, 552f to 204f, 664f to 246f, 764f to 214f,
)

private val PLACES = listOf(
    Triple(36f, 330f, "LONGREACH"),
    Triple(214f, 352f, "COLD HARBOUR"),
    Triple(392f, 372f, "STONE POINT"),
    Triple(452f, 88f, "OUTER BANK"),
    Triple(612f, 356f, "EAST SANDS"),
    Triple(704f, 168f, "THE ROADS"),
)

