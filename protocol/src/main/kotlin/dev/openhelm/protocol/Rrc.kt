package dev.openhelm.protocol

/**
 * The MFD remote-control wire protocol ("RRC").
 *
 * Implemented from a protocol description — frame layout, opcode numbers and field widths — not
 * from any vendor source. See CLEAN-ROOM.md.
 *
 * Every frame is a 9-byte header followed by a payload:
 * ```
 * offset  bytes         meaning
 * 0..3    45 43 52 52   magic "ECRR"
 * 4       01            constant
 * 5       vv            protocol version (see [RrcVersion])
 * 6       op            opcode
 * 7..8    ll hh         payload length, little-endian
 * 9..     ....          payload
 * ```
 */
public object Rrc {
    /** Magic bytes, as they appear on the wire. */
    public val MAGIC: ByteArray = byteArrayOf(0x45, 0x43, 0x52, 0x52) // "ECRR"

    public const val HEADER_LEN: Int = 9
    public const val CONST_BYTE: Int = 0x01

    public const val OP_BUTTON: Int = 0x01
    public const val OP_ZOOM: Int = 0x02
    public const val OP_TOUCH: Int = 0x03

    /** Default when a device advertises no parsable version. */
    public const val DEFAULT_VERSION: Int = 0x01

    private fun header(opcode: Int, payloadLen: Int, version: Int): ByteArray = byteArrayOf(
        MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3],
        CONST_BYTE.toByte(),
        version.toByte(),
        opcode.toByte(),
        (payloadLen and 0xFF).toByte(),
        ((payloadLen shr 8) and 0xFF).toByte(),
    )

    /**
     * A key press or release.
     *
     * Press and release are **separate** frames and the caller must send both. The MFD implements
     * auto-repeat itself: holding a direction key down produces continuous cursor movement, so the
     * gap between DOWN and UP is the control the user actually has. Never synthesise an immediate
     * UP after a DOWN — that turns a hold into a tap and makes coarse cursor movement impossible.
     */
    public fun button(key: MfdKey, action: KeyAction, version: Int = DEFAULT_VERSION): ByteArray =
        header(OP_BUTTON, 2, version) + byteArrayOf(key.code.toByte(), action.code.toByte())

    /**
     * Zoom the chart.
     *
     * Despite carrying what look like coordinates, this opcode does **not** move a cursor — it
     * changes the range. Verified on hardware: sending plausible screen positions stepped the chart
     * 5nm → 4nm → 2500ft while the cursor never moved. Negative values zoom out.
     *
     * Modelling this as a pointer is the single easiest mistake to make with this protocol, which
     * is why the function is not called `pointer`.
     */
    public fun zoom(x: Int, y: Int, version: Int = DEFAULT_VERSION): ByteArray =
        header(OP_ZOOM, 4, version) + le16(x) + le16(y)

    /**
     * A touch at an absolute position.
     *
     * [x] and [y] are **normalised to 0..65535 across the video area**, not pixels — which is what
     * makes the protocol resolution-independent. Use [normalise] rather than converting by hand.
     *
     * [seq] is zero on DOWN and UP, and increments per MOVE within one gesture. A tap is DOWN+UP at
     * one point; a drag is DOWN → n×MOVE → UP. [TouchGesture] maintains this for you.
     *
     * Not confirmed against non-touch MFDs — see docs. Callers should keep a key-based fallback.
     */
    public fun touch(action: TouchAction, seq: Int, x: Int, y: Int, version: Int = DEFAULT_VERSION): ByteArray =
        header(OP_TOUCH, 6, version) +
            byteArrayOf(action.code.toByte(), (seq and 0xFF).toByte()) +
            le16(x) + le16(y)

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}

/** Press or release of a key. */
public enum class KeyAction(public val code: Int) { DOWN(1), UP(2) }

/** Phase of a touch gesture. */
public enum class TouchAction(public val code: Int) { DOWN(1), UP(2), MOVE(3) }

/**
 * Keys the MFD understands.
 *
 * The values are Windows virtual-key codes — an implementation detail of the device, reproduced
 * here because the wire format requires it.
 */
public enum class MfdKey(public val code: Int) {
    LEFT(37), UP(38), RIGHT(39), DOWN(40),
    OK(13),
    HOME(118), MENU(120), BACK(27),
    RANGE_OUT(33), RANGE_IN(34),
    SWITCH(122), WPT(119);

    public companion object {
        private val byCode = entries.associateBy { it.code }
        public fun fromCode(code: Int): MfdKey? = byCode[code]
    }
}

/** Full-scale value of a normalised touch coordinate. */
public const val TOUCH_MAX: Int = 0xFFFF

/**
 * Map a pixel position within a video area of [width]×[height] onto the protocol's 0..65535 space.
 *
 * Out-of-range input is clamped rather than wrapped: a touch just past the edge should land on the
 * edge, never on the opposite side of the chart. A zero-sized view yields 0 rather than dividing by
 * zero — views are measured after they are constructed, so this really happens.
 *
 * **Rounds rather than truncating.** The original implementation truncated, which biases every
 * coordinate toward the top-left by up to one step and puts the exact centre of an 800px-wide view
 * at 32767 instead of 32768. The difference is ~1/65535 of the screen — far below one pixel, so
 * either is functionally correct — but rounding is unbiased, and it makes this agree exactly with
 * the reference implementation the golden test vectors come from.
 */
public fun normalise(x: Float, y: Float, width: Int, height: Int): Pair<Int, Int> {
    fun scale(v: Float, size: Int): Int {
        if (size <= 0) return 0
        val n = Math.round(v / size * TOUCH_MAX)
        return n.coerceIn(0, TOUCH_MAX)
    }
    return scale(x, width) to scale(y, height)
}

/**
 * Tracks one touch gesture so callers do not have to manage [Rrc.touch]'s `seq` field by hand.
 *
 * Not thread-safe: drive it from a single input source.
 */
public class TouchGesture(private val version: Int = Rrc.DEFAULT_VERSION) {
    private var seq = 0

    public fun down(x: Int, y: Int): ByteArray {
        seq = 0
        return Rrc.touch(TouchAction.DOWN, 0, x, y, version)
    }

    public fun move(x: Int, y: Int): ByteArray {
        seq = (seq + 1) and 0xFF
        return Rrc.touch(TouchAction.MOVE, seq, x, y, version)
    }

    public fun up(x: Int, y: Int): ByteArray {
        seq = 0
        return Rrc.touch(TouchAction.UP, 0, x, y, version)
    }
}
