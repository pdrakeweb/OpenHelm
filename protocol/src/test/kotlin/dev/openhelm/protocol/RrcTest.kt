package dev.openhelm.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conformance against **golden byte vectors**.
 *
 * These are not hand-computed from the spec — they were produced by an independent, separately
 * tested reference implementation of the same protocol, itself validated frame-for-frame against a
 * real device. Two implementations agreeing byte-for-byte is much stronger evidence than either
 * agreeing with its own author's reading of the spec.
 */
class RrcTest {

    private fun bytes(hex: String) = hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // -- encoding ------------------------------------------------------------------------------

    @Test
    fun `button frame matches the reference encoder`() {
        assertContentEquals(
            bytes("45 43 52 52 01 10 01 02 00 78 01"),
            Rrc.button(MfdKey.MENU, KeyAction.DOWN, version = 0x10),
        )
        assertContentEquals(
            bytes("45 43 52 52 01 01 01 02 00 1b 02"),
            Rrc.button(MfdKey.BACK, KeyAction.UP, version = 0x01),
        )
    }

    @Test
    fun `zoom frame carries two little-endian signed int16`() {
        assertContentEquals(
            bytes("45 43 52 52 01 01 02 04 00 0c 00 fc ff"), // 12, -4
            Rrc.zoom(12, -4, version = 0x01),
        )
    }

    @Test
    fun `touch frames match the reference encoder`() {
        val (cx, cy) = normalise(400f, 240f, 800, 480)
        assertContentEquals(
            bytes("45 43 52 52 01 10 03 06 00 01 00 00 80 00 80"),
            Rrc.touch(TouchAction.DOWN, 0, cx, cy, version = 0x10),
        )
        val (mx, my) = normalise(600f, 300f, 800, 480)
        assertContentEquals(
            bytes("45 43 52 52 01 10 03 06 00 03 07 ff bf ff 9f"),
            Rrc.touch(TouchAction.MOVE, 7, mx, my, version = 0x10),
        )
        val (ex, ey) = normalise(800f, 480f, 800, 480)
        assertContentEquals(
            bytes("45 43 52 52 01 10 03 06 00 02 00 ff ff ff ff"),
            Rrc.touch(TouchAction.UP, 0, ex, ey, version = 0x10),
        )
    }

    @Test
    fun `every frame is payload plus nine bytes`() {
        assertEquals(11, Rrc.button(MfdKey.OK, KeyAction.DOWN).size)
        assertEquals(13, Rrc.zoom(0, 0).size)
        assertEquals(15, Rrc.touch(TouchAction.DOWN, 0, 0, 0).size)
    }

    // -- normalisation -------------------------------------------------------------------------

    @Test
    fun `normalise maps the video area onto 0 to 65535`() {
        assertEquals(0 to 0, normalise(0f, 0f, 800, 480))
        // 32768, not TOUCH_MAX/2 (= 32767): the midpoint rounds up. See `normalise`'s note on
        // rounding vs truncation -- this is the value the reference encoder produces.
        assertEquals(32768 to 32768, normalise(400f, 240f, 800, 480))
        assertEquals(TOUCH_MAX to TOUCH_MAX, normalise(800f, 480f, 800, 480))
    }

    @Test
    fun `normalise clamps rather than wrapping`() {
        // A touch just off the edge must land on the edge -- never on the opposite side of the chart.
        assertEquals(0 to TOUCH_MAX, normalise(-50f, 9999f, 800, 480))
    }

    @Test
    fun `normalise survives a zero-sized view`() {
        assertEquals(0 to 0, normalise(10f, 10f, 0, 0)) // before layout, width/height can be 0
    }

    // -- gesture sequencing --------------------------------------------------------------------

    @Test
    fun `gesture numbers moves and zeroes the bookends`() {
        val g = TouchGesture()
        fun seqOf(f: ByteArray) = f[10].toInt() and 0xFF
        fun actionOf(f: ByteArray) = f[9].toInt() and 0xFF

        assertEquals(0, seqOf(g.down(1, 1)))
        assertEquals(1, seqOf(g.move(2, 2)))
        assertEquals(2, seqOf(g.move(3, 3)))
        assertEquals(3, seqOf(g.move(4, 4)))
        val up = g.up(4, 4)
        assertEquals(0, seqOf(up))
        assertEquals(TouchAction.UP.code, actionOf(up))

        // a second gesture restarts numbering
        assertEquals(0, seqOf(g.down(9, 9)))
        assertEquals(1, seqOf(g.move(9, 9)))
    }

    @Test
    fun `gesture sequence wraps within a byte`() {
        val g = TouchGesture()
        g.down(0, 0)
        repeat(255) { g.move(0, 0) }
        assertEquals(0, g.move(0, 0)[10].toInt() and 0xFF) // 255 -> 0, not 256
    }

    // -- decoding ------------------------------------------------------------------------------

    @Test
    fun `decoder round-trips every opcode`() {
        val d = RrcDecoder()
        val frames = d.feed(
            Rrc.button(MfdKey.HOME, KeyAction.DOWN, 0x10) +
                Rrc.zoom(12, -4, 0x10) +
                Rrc.touch(TouchAction.MOVE, 9, 40000, 50000, 0x10),
        )
        assertEquals(3, frames.size)

        val b = assertIs<RrcFrame.Button>(frames[0])
        assertEquals(MfdKey.HOME, b.key)
        assertEquals(KeyAction.DOWN, b.action)
        assertEquals(0x10, b.version)

        val z = assertIs<RrcFrame.Zoom>(frames[1])
        assertEquals(12, z.x)
        assertEquals(-4, z.y) // negative survives the signed int16 round trip

        val t = assertIs<RrcFrame.Touch>(frames[2])
        assertEquals(TouchAction.MOVE, t.action)
        assertEquals(9, t.seq)
        assertEquals(40000, t.x) // above 0x7FFF: must fold back to unsigned, not stay negative
        assertEquals(50000, t.y)
    }

    @Test
    fun `touch decodes back to the pixel it came from`() {
        val d = RrcDecoder()
        for ((px, py) in listOf(0 to 0, 255 to 156, 400 to 240, 582 to 281, 799 to 479)) {
            val (nx, ny) = normalise(px.toFloat(), py.toFloat(), 800, 480)
            val t = assertIs<RrcFrame.Touch>(d.feed(Rrc.touch(TouchAction.DOWN, 0, nx, ny)).single())
            val (fx, fy) = t.fraction
            assertTrue(kotlin.math.abs((fx * 800).toInt() - px) <= 1, "x drifted for $px")
            assertTrue(kotlin.math.abs((fy * 480).toInt() - py) <= 1, "y drifted for $py")
        }
    }

    @Test
    fun `decoder reassembles frames split across reads`() {
        // TCP gives no message boundaries. Feed one byte at a time -- the worst case.
        val blob = Rrc.button(MfdKey.LEFT, KeyAction.DOWN) +
            Rrc.touch(TouchAction.DOWN, 0, 100, 200) +
            Rrc.zoom(1, 2)
        val d = RrcDecoder()
        val got = blob.flatMap { d.feed(byteArrayOf(it)) }
        assertEquals(3, got.size)
        assertIs<RrcFrame.Button>(got[0])
        assertIs<RrcFrame.Touch>(got[1])
        assertIs<RrcFrame.Zoom>(got[2])
    }

    @Test
    fun `an unknown opcode is reported and does not break the stream`() {
        // The protocol is reverse-engineered, so undiscovered opcodes should be expected. Throwing
        // here would drop the connection and lose every frame behind the unknown one.
        val d = RrcDecoder()
        val unknown = bytes("45 43 52 52 01 10 07 02 00 aa bb")
        val got = d.feed(unknown + Rrc.button(MfdKey.OK, KeyAction.UP))
        assertEquals(2, got.size)
        val u = assertIs<RrcFrame.Unknown>(got[0])
        assertEquals(7, u.opcode)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), u.payload)
        assertEquals(MfdKey.OK, assertIs<RrcFrame.Button>(got[1]).key)
    }

    @Test
    fun `decoder resynchronises past garbage`() {
        val d = RrcDecoder()
        val got = d.feed(byteArrayOf(0, 1, 2, 3, 99) + Rrc.button(MfdKey.WPT, KeyAction.DOWN))
        assertEquals(MfdKey.WPT, assertIs<RrcFrame.Button>(got.single()).key)
    }

    @Test
    fun `an unmapped keycode keeps its raw value`() {
        val d = RrcDecoder()
        val frame = bytes("45 43 52 52 01 01 01 02 00 63 01") // key 0x63, not in the table
        val b = assertIs<RrcFrame.Button>(d.feed(frame).single())
        assertNull(b.key)
        assertEquals(0x63, b.rawKey)
    }
}
