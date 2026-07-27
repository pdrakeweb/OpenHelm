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
 */
internal fun DrawScope.drawSimulatedChart(measurer: TextMeasurer, frame: Int) {
    val u = size.width / VIRTUAL_W
    fun x(v: Float) = v * u
    fun y(v: Float) = v * (size.height / VIRTUAL_H)

    val t = frame / 15f // seconds

    drawChartBase(u, ::x, ::y, measurer, t)
    drawDataBar(::x, ::y, u, measurer, t)
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
    measurer: TextMeasurer,
    t: Float,
) {
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

    // Graticule.
    val gStroke = Stroke(width = 1f * u)
    for (gx in 1..4) {
        val px = VIRTUAL_W * gx / 5f
        drawLine(GraticuleInk, Offset(x(px), y(44f)), Offset(x(px), y(VIRTUAL_H)), gStroke.width)
    }
    for (gy in 1..3) {
        val py = 44f + (VIRTUAL_H - 44f) * gy / 4f
        drawLine(GraticuleInk, Offset(0f, y(py)), Offset(x(VIRTUAL_W), y(py)), gStroke.width)
    }

    // Spot soundings: fixed positions, so the chart does not shimmer between frames.
    val soundingStyle = TextStyle(color = ChartInk, fontSize = 8.sp, fontFamily = FontFamily.SansSerif)
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
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.SansSerif,
    )
    PLACES.forEach { (px, py, name) ->
        drawText(measurer.measure(name, nameStyle), topLeft = Offset(x(px), y(py)))
    }

    // Scale bar and orientation. Kept clear of the data bar, which is painted over the chart
     // afterwards and swallowed both labels when they sat any higher.
    val barY = y(66f)
    val barLeft = x(24f)
    val barRight = x(120f)
    drawLine(ChartInk, Offset(barLeft, barY), Offset(barRight, barY), strokeWidth = 1.4f * u)
    drawLine(ChartInk, Offset(barLeft, barY - 4f * u), Offset(barLeft, barY + 4f * u), strokeWidth = 1.4f * u)
    drawLine(ChartInk, Offset(barRight, barY - 4f * u), Offset(barRight, barY + 4f * u), strokeWidth = 1.4f * u)
    val scaleStyle = TextStyle(color = ChartInk, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    val scaleLine = measurer.measure("2 nm", scaleStyle)
    drawText(scaleLine, topLeft = Offset(barRight + x(10f), barY - scaleLine.size.height / 2f))
    val northLine = measurer.measure("North-Up", scaleStyle)
    drawText(northLine, topLeft = Offset(x(400f) - northLine.size.width / 2f, barY - northLine.size.height / 2f))
}

// ---- data bar ------------------------------------------------------------------------------

private fun DrawScope.drawDataBar(
    x: (Float) -> Float,
    y: (Float) -> Float,
    u: Float,
    measurer: TextMeasurer,
    t: Float,
) {
    val h = y(44f)
    drawRect(Color(0xFF1B2A38), size = Size(size.width, h))
    drawLine(Color(0xFF3E5265), Offset(0f, h), Offset(size.width, h), strokeWidth = 1f * u)

    val labelStyle = TextStyle(color = Color(0xFF90A4AE), fontSize = 8.sp, fontWeight = FontWeight.Medium)
    val valueStyle = TextStyle(
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.SansSerif,
    )

    // Values drift slowly, so the bar reads as live instrumentation rather than a static caption.
    val sog = 6.4f + sin(t / 5.5f) * 0.6f
    val cog = 74f + sin(t / 7.3f) * 5f
    val dpt = 18.2f + sin(t / 4.1f) * 2.4f

    val fields = listOf(
        "SOG" to "%.1f kn".format(sog),
        "COG" to "%03d°".format(cog.roundToInt().mod(360)),
        "DPT" to "%.1f m".format(dpt),
        // A clock rather than a stopwatch: minutes from a fixed afternoon start, so the field
        // reads the way the real one does instead of counting up from zero.
        "TIME" to "%02d:%02d".format(((CLOCK_START_MIN + t / 60) / 60).toInt().mod(24), (CLOCK_START_MIN + t / 60).toInt().mod(60)),
    )

    var cx = x(14f)
    fields.forEach { (label, value) ->
        drawText(measurer.measure(label, labelStyle), topLeft = Offset(cx, y(6f)))
        val v = measurer.measure(value, valueStyle)
        drawText(v, topLeft = Offset(cx, y(17f)))
        cx += maxOf(v.size.width.toFloat(), x(72f)) + x(24f)
    }

    drawMenuButton(x, y, u, measurer)
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
    y: (Float) -> Float,
    u: Float,
    measurer: TextMeasurer,
) {
    val right = x(786f)
    val left = x(694f)
    val top = y(7f)
    val bottom = y(37f)

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

    // Three bars, then the word.
    val barX = left + x(11f)
    val barW = x(15f)
    repeat(3) { i ->
        val by = top + (bottom - top) / 2f + (i - 1) * 6f * u
        drawLine(
            Color(0xFFE3EAF0),
            Offset(barX, by),
            Offset(barX + barW, by),
            strokeWidth = 2f * u,
        )
    }
    val label = measurer.measure(
        "Menu",
        TextStyle(color = Color(0xFFE3EAF0), fontSize = 12.sp, fontWeight = FontWeight.Medium),
    )
    drawText(
        label,
        topLeft = Offset(barX + barW + x(9f), (top + bottom) / 2f - label.size.height / 2f),
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

private const val VIRTUAL_W = 800f
private const val VIRTUAL_H = 480f

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

